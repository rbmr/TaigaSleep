package com.rbmr.taigasleep

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the package structure of docs/architecture.md true: every Kotlin file of the app, in the
 * main, debug and release source sets, may import only the packages its own package is allowed to
 * depend on. The table below is the one place that says so, and [theTableMatchesTheDocument] keeps
 * it equal to the document.
 *
 * A needed dependency that is not in the table is a signal to move code, not to widen the table.
 */
class ArchitectureTest {
  @Test
  fun `no file imports a package its own package may not depend on`() {
    val sources = appSources()
    assertTrue("no Kotlin sources found under ${moduleDir()}", sources.isNotEmpty())

    val violations = sources.flatMap { (name, source) -> Architecture.violations(name, source) }

    assertTrue(
        "The package dependencies in docs/architecture.md are broken (${violations.size}):\n" +
            violations.joinToString("\n") { "  $it" },
        violations.isEmpty(),
    )
  }

  @Test
  fun `the table matches the document`() {
    val document = File(moduleDir().parentFile, "docs/architecture.md")
    assertTrue("$document is missing", document.isFile)

    assertEquals(Architecture.ALLOWED, documentedTable(document.readLines()))
  }

  @Test
  fun `the checker reports an import the table does not allow`() {
    val violations =
        Architecture.violations(
            "tracker/Bad.kt",
            """
            package com.rbmr.taigasleep.tracker

            import com.rbmr.taigasleep.diagnostics.Diagnostics
            import com.rbmr.taigasleep.recording.RecordingState
            """
                .trimIndent(),
        )

    assertEquals(
        listOf(
            "tracker/Bad.kt -> import com.rbmr.taigasleep.recording.RecordingState: " +
                "`tracker` may not depend on `recording`"
        ),
        violations,
    )
  }

  @Test
  fun `the checker accepts what the table allows`() {
    val violations =
        Architecture.violations(
            "sync/Good.kt",
            """
            package com.rbmr.taigasleep.sync

            import com.rbmr.taigasleep.BuildConfig
            import com.rbmr.taigasleep.R
            import com.rbmr.taigasleep.backend.RemoteRepository
            import com.rbmr.taigasleep.recording.store.RecordingStore
            import com.rbmr.taigasleep.sync.UploadStore
            """
                .trimIndent(),
        )

    assertEquals(emptyList<String>(), violations)
  }

  @Test
  fun `ui may depend on every other package and diagnostics on none`() {
    val everything = Architecture.ALLOWED.keys.filter { it != "ui" }
    val uiImports = everything.joinToString("\n") { "import com.rbmr.taigasleep.$it.X" }
    assertEquals(
        emptyList<String>(),
        Architecture.violations("ui/A.kt", "package com.rbmr.taigasleep.ui\n$uiImports"),
    )

    val leaf =
        Architecture.violations(
            "diagnostics/A.kt",
            "package com.rbmr.taigasleep.diagnostics\nimport com.rbmr.taigasleep.tracker.X",
        )
    assertEquals(1, leaf.size)
  }

  @Test
  fun `a package that is not in the table is reported`() {
    val declared =
        Architecture.violations("gadgets/A.kt", "package com.rbmr.taigasleep.gadgets.deep")
    val imported =
        Architecture.violations(
            "ui/A.kt",
            "package com.rbmr.taigasleep.ui\nimport com.rbmr.taigasleep.gadgets.Gadget",
        )

    assertEquals(1, declared.size)
    assertTrue(declared.single(), "`gadgets` is not in the architecture table" in declared.single())
    assertEquals(1, imported.size)
    assertTrue(imported.single(), "`gadgets` is not in the architecture table" in imported.single())
  }

  @Test
  fun `the root package is wiring, unrestricted itself and not importable`() {
    val wiring =
        Architecture.violations(
            "TaigaApplication.kt",
            """
            package com.rbmr.taigasleep

            import com.rbmr.taigasleep.library.LibraryLiveSync
            import com.rbmr.taigasleep.recording.RecordingHooks
            import com.rbmr.taigasleep.ui.TaigaApp
            """
                .trimIndent(),
        )
    assertEquals(emptyList<String>(), wiring)

    val importing =
        Architecture.violations(
            "ui/A.kt",
            """
            package com.rbmr.taigasleep.ui

            import com.rbmr.taigasleep.AppSettings
            import com.rbmr.taigasleep.MainActivity
            """
                .trimIndent(),
        )
    assertEquals(2, importing.size)
    assertTrue(importing.first(), "`ui` may not depend on the app wiring" in importing.first())
  }

  @Test
  fun `the debug seam may be used by every package and uses only the types it replaces`() {
    val users =
        Architecture.ALLOWED.keys.flatMap {
          Architecture.violations(
              "$it/A.kt",
              "package com.rbmr.taigasleep.$it\nimport com.rbmr.taigasleep.debug.DebugTools",
          )
        }
    assertEquals(emptyList<String>(), users)

    val seamUsing =
        Architecture.violations(
            "debug/A.kt",
            """
            package com.rbmr.taigasleep.debug

            import com.rbmr.taigasleep.guard.PreviewDevice
            import com.rbmr.taigasleep.tracker.SleepSensor
            import com.rbmr.taigasleep.recording.RecordingService
            """
                .trimIndent(),
        )
    assertEquals(1, seamUsing.size)
    assertTrue(seamUsing.single(), "`debug` may not depend on `recording`" in seamUsing.single())
  }

  @Test
  fun `a fully qualified reference is an import too`() {
    val violations =
        Architecture.violations(
            "insights/A.kt",
            """
            package com.rbmr.taigasleep.insights

            fun f(x: com.rbmr.taigasleep.recording.store.RecordingConfig) = x
            """
                .trimIndent(),
        )

    assertEquals(1, violations.size)
    assertTrue(violations.single(), "`insights` may not depend on `recording`" in violations.single())
  }

  @Test
  fun `a file with no package or a foreign package is reported`() {
    assertEquals(1, Architecture.violations("A.kt", "fun f() = Unit").size)
    assertEquals(1, Architecture.violations("A.kt", "package org.example").size)
  }

  private fun documentedTable(lines: List<String>): Map<String, Set<String>> {
    val section = lines.dropWhile { it.trim() != "## App structure" }.drop(1).takeWhile { !it.startsWith("## ") }
    val rows = section.mapNotNull { ROW.matchEntire(it.trim()) }
    assertTrue("no package table found under '## App structure'", rows.isNotEmpty())

    val packages = rows.map { it.groupValues[1] }
    return rows.associate { row ->
      val pkg = row.groupValues[1]
      val dependencies = row.groupValues[2]
      pkg to
          when {
            "every other package" in dependencies -> packages.filter { it != pkg }.toSet()
            "nothing" in dependencies -> emptySet()
            else -> NAME.findAll(dependencies).map { it.groupValues[1] }.toSet()
          }
    }
  }

  private fun moduleDir(): File =
      listOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }.canonicalFile

  /** Every non-test Kotlin file of the app as (path relative to the module, its text). */
  private fun appSources(): List<Pair<String, String>> {
    val module = moduleDir()
    return listOf("main", "debug", "release").flatMap { set ->
      File(module, "src/$set/java")
          .walkTopDown()
          .filter { it.isFile && it.extension == "kt" }
          .map { it.relativeTo(module).path to it.readText() }
          .toList()
    }
  }

  private companion object {
    /** `| \`package\` | responsibility | may depend on |` */
    val ROW = Regex("""\|\s*`(\w+)`\s*\|.*\|\s*([^|]*?)\s*\|""")
    val NAME = Regex("`(\\w+)`")
  }
}

/** The rules, free of any file access so the tests can feed them synthetic sources. */
private object Architecture {
  private const val ROOT = "com.rbmr.taigasleep"

  /**
   * Package -> the packages it may depend on: the table of docs/architecture.md. Importing your own
   * package is always fine, and so is importing the [SEAM].
   */
  val ALLOWED: Map<String, Set<String>> =
      mapOf(
          "tracker" to setOf("diagnostics"),
          "recording" to setOf("tracker", "diagnostics"),
          "guard" to setOf("diagnostics"),
          "backend" to setOf("diagnostics"),
          "sync" to setOf("recording", "backend", "insights", "diagnostics"),
          "insights" to setOf("backend", "diagnostics"),
          "library" to setOf("recording", "sync", "insights", "backend", "diagnostics"),
          "ui" to
              setOf(
                  "tracker",
                  "recording",
                  "guard",
                  "backend",
                  "sync",
                  "insights",
                  "library",
                  "diagnostics",
              ),
          "diagnostics" to emptySet(),
      )

  /**
   * The build-variant seam: `src/debug` has the real tools and `src/release` an empty twin, so any
   * package may reach them. It stands in for tracker and guidance types, so it may use those.
   */
  const val SEAM = "debug"
  private val SEAM_MAY_USE = setOf("tracker", "guard")

  /** Generated by the build for the whole app, so no package owns them. */
  private val GENERATED = setOf("R", "BuildConfig")

  private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
  private val IMPORT = Regex("""^\s*import\s+com\.rbmr\.taigasleep\.(\w+|\*)[\w.*]*""")
  private val QUALIFIED = Regex("""com\.rbmr\.taigasleep\.([a-z][a-z0-9_]*)\.[A-Za-z][\w.]*""")

  /** One readable line per rule a file breaks; [file] only labels them. */
  fun violations(file: String, source: String): List<String> {
    val declared = PACKAGE.find(source)?.groupValues?.get(1)
    val owner =
        when {
          declared == null -> return listOf("$file: no package declaration")
          declared == ROOT -> null
          declared.startsWith("$ROOT.") -> declared.removePrefix("$ROOT.").substringBefore('.')
          else -> return listOf("$file: package $declared is outside $ROOT")
        }
    if (owner == null) return emptyList()
    if (owner != SEAM && owner !in ALLOWED) return listOf("$file: ${unknown(owner)}")

    val found = mutableListOf<String>()
    for (line in source.lineSequence()) {
      val importMatch = IMPORT.find(line)
      if (importMatch != null) {
        judge(file, owner, importMatch.value.trim(), importMatch.groupValues[1])?.let(found::add)
        continue
      }
      if (line.trimStart().startsWith("package ")) continue
      for (reference in QUALIFIED.findAll(line)) {
        judge(file, owner, reference.value, reference.groupValues[1])?.let(found::add)
      }
    }
    return found.distinct()
  }

  /** The violation of [owner] depending on what [target] names, or null when that is allowed. */
  private fun judge(file: String, owner: String, text: String, target: String): String? {
    val what = text.removePrefix("import ").trim()
    val prefix = "$file -> ${if (text.startsWith("import")) "import" else "reference"} $what"
    return when {
      target == owner || target == SEAM || target in GENERATED -> null
      target[0].isUpperCase() || target == "*" ->
          "$prefix: `$owner` may not depend on the app wiring (`$target`) in the root package"
      target !in ALLOWED -> "$prefix: ${unknown(target)}"
      target !in (if (owner == SEAM) SEAM_MAY_USE else ALLOWED.getValue(owner)) ->
          "$prefix: `$owner` may not depend on `$target`"
      else -> null
    }
  }

  private fun unknown(pkg: String) =
      "`$pkg` is not in the architecture table: decide its place in docs/architecture.md and add " +
          "it to Architecture.ALLOWED on purpose"
}
