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

  /**
   * The screens draw. Only a `ViewModel` may reach into the stores, so every other file under `ui/`
   * keeps exactly the number of imports from the packages that hold state that it has in the
   * baseline. The check fails in both directions: a new import is a regression, and a removed one
   * must lower the baseline in the same change.
   */
  @Test
  fun `ui files import no more of the stores than the baseline allows`() {
    val actual = UiImports.counts(uiSources())
    val baseline = UiImports.read(uiBaseline())
    val differences = UiImports.differences(actual, baseline)

    assertTrue(
        "app/src/test/resources/ui-import-baseline.txt is out of date (" +
            "${differences.size}):\n" +
            differences.joinToString("\n") { "  $it" } +
            "\n\nWhat the baseline would be:\n" +
            UiImports.lines(actual).joinToString("\n") { "  $it" },
        differences.isEmpty(),
    )
  }

  @Test
  fun `the ratchet reports a file that exceeds its baseline and one that drops below it`() {
    val baseline = mapOf("screens/Worse.kt" to 1, "screens/Better.kt" to 4, "screens/Gone.kt" to 2)
    val actual = mapOf("screens/Worse.kt" to 3, "screens/Better.kt" to 0, "screens/New.kt" to 2)

    assertEquals(
        listOf(
            "screens/Better.kt imports 0 of sync/backend/tracker/recording/guard, " +
                "baseline says 4: lower it in the same change",
            "screens/Gone.kt is in the baseline but does not exist: remove it",
            "screens/New.kt is not in the baseline: add `screens/New.kt 2`",
            "screens/Worse.kt imports 3 of sync/backend/tracker/recording/guard, " +
                "baseline says 1: move the call into its view model",
        ),
        UiImports.differences(actual, baseline),
    )
  }

  /**
   * Outside `sync`, the package's surface is [SyncSurface.ALLOWED]: the service that owns the
   * sequences, the state a screen shows, the settings, and the data the library composes. A new
   * name is a decision about what the package exposes, so the test fails until it is written down.
   */
  @Test
  fun `files outside sync use only the sync surface`() {
    val violations = SyncSurface.violations(appSources().toMap())

    assertTrue(
        "A file outside `sync` reaches past its surface (${violations.size}):\n" +
            violations.joinToString("\n") { "  $it" },
        violations.isEmpty(),
    )
  }

  @Test
  fun `the sync surface checker reports a file that uses an internal piece`() {
    val violations =
        SyncSurface.violations(
            mapOf(
                "library/A.kt" to
                    """
                    package com.rbmr.taigasleep.library

                    import com.rbmr.taigasleep.sync.SyncService
                    import com.rbmr.taigasleep.sync.UploadScheduler
                    import com.rbmr.taigasleep.sync.UploadPhase
                    """
                        .trimIndent(),
                "sync/Inside.kt" to
                    """
                    package com.rbmr.taigasleep.sync

                    import com.rbmr.taigasleep.sync.UploadScheduler
                    """
                        .trimIndent(),
                "TaigaApplication.kt" to
                    """
                    package com.rbmr.taigasleep

                    fun f() = com.rbmr.taigasleep.sync.UploadCoordinator
                    """
                        .trimIndent(),
            )
        )

    assertEquals(
        listOf(
            "library/A.kt -> UploadScheduler: nothing outside `sync` may use it; add it to " +
                "SyncSurface.ALLOWED on purpose",
            "TaigaApplication.kt -> UploadCoordinator: nothing outside `sync` may use it; add it " +
                "to SyncSurface.ALLOWED on purpose",
        ),
        violations,
    )
  }

  private fun uiBaseline(): File = File(moduleDir(), "src/test/resources/ui-import-baseline.txt")

  /** Every non-view-model Kotlin file under `ui/` in the main source set, keyed by its path in `ui/`. */
  private fun uiSources(): Map<String, String> {
    val marker = "/ui/"
    return appSources()
        .filter { (path, _) -> path.startsWith("src/main/java/") && marker in path }
        .associate { (path, source) -> path.substringAfter(marker) to source }
        .filterKeys { !it.endsWith("ViewModel.kt") }
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
          "guard" to setOf("tracker", "diagnostics"),
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

/**
 * Counts what a screen file imports from the packages that own state. The number, not the names, is
 * what the baseline holds: a screen either keeps its dependencies or its view model takes one over.
 */
private object UiImports {
  private val PACKAGES = setOf("sync", "backend", "tracker", "recording", "guard")

  /** One import of one of the packages, or a fully qualified reference to a type in one. */
  private val IMPORT = Regex("""^\s*import\s+com\.rbmr\.taigasleep\.(\w+)[\w.*]*""")
  private val REFERENCE = Regex("""com\.rbmr\.taigasleep\.([a-z][a-z0-9_]*)\.[A-Za-z][\w.]*""")

  fun counts(sources: Map<String, String>): Map<String, Int> =
      sources.mapValues { (_, source) -> count(source) }

  fun count(source: String): Int =
      source.lineSequence().count { line ->
        if (line.trimStart().startsWith("package ")) {
          false
        } else {
          val target =
              IMPORT.find(line)?.groupValues?.get(1)
                  ?: REFERENCE.find(line)?.groupValues?.get(1)
          target != null && target in PACKAGES
        }
      }

  /** `path count` for every file, sorted, as the baseline file holds them. */
  fun lines(counts: Map<String, Int>): List<String> =
      counts.keys.sorted().map { "$it ${counts.getValue(it)}" }

  fun read(file: File): Map<String, Int> =
      file.readLines()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }
          .associate { line ->
            val at = line.lastIndexOf(' ')
            require(at > 0) { "$file: '$line' is not 'path count'" }
            line.substring(0, at) to line.substring(at + 1).toInt()
          }

  fun differences(actual: Map<String, Int>, baseline: Map<String, Int>): List<String> =
      (actual.keys + baseline.keys).sorted().mapNotNull { path ->
        val now = actual[path]
        val allowed = baseline[path]
        when {
          now == null -> "$path is in the baseline but does not exist: remove it"
          allowed == null -> "$path is not in the baseline: add `$path $now`"
          now > allowed ->
              "$path imports $now of ${PACKAGES.joinToString("/")}, baseline says $allowed: " +
                  "move the call into its view model"
          now < allowed ->
              "$path imports $now of ${PACKAGES.joinToString("/")}, baseline says $allowed: " +
                  "lower it in the same change"
          else -> null
        }
      }
}

/**
 * What a file outside `sync` may name. The six pieces that used to be called from the screens, the
 * library, the application and the workers (`UploadScheduler`, `UploadCoordinator`,
 * `AnalysisService`, `RecordingDeleter`, `RawRetention`, `UploadNotifications`) are not here:
 * `SyncService` is the only way to reach them, so a call site that needs one is a call site that
 * goes through the service.
 */
private object SyncSurface {
  private const val PACKAGE_NAME = "com.rbmr.taigasleep.sync"

  /** Every name the rest of the app may import from `sync`. Adding one is a decision. */
  val ALLOWED =
      setOf(
          // The entry point: the whole outside surface of the package.
          "SyncService",
          // What a screen shows about a night that is moving, and why it failed.
          "UploadPhase",
          "UploadFailure",
          // The settings the screens read and write.
          "UploadSettings",
          "AutoUploadSettings",
          "RawRetentionMode",
          // The data a screen or the library carries: one night's sync state, what an explicit raw
          // action did, one queued server delete, one read of a night and its analyses.
          "UploadRecord",
          "RawActionResult",
          "PendingDelete",
          "RecordingSnapshot",
          // Where that state is kept, and the live stream the library drives.
          "ResultCache",
          "RemoteHistoryCache",
          "PendingDeleteStore",
          "LiveSync",
          "LiveSyncTarget",
          // A night's key, which installed algorithm a recording can feed, and the server's list
          // without the recordings the app deleted.
          "remoteKey",
          "recordingIdOf",
          "compatible",
          "withoutRecordings",
      )

  private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
  private val IMPORT = Regex("""^\s*import\s+com\.rbmr\.taigasleep\.sync\.(\w+|\*)""")
  private val REFERENCE = Regex("""com\.rbmr\.taigasleep\.sync\.([A-Z]\w*)""")

  /** One line per name a file outside `sync` uses without it being on the allowlist. */
  fun violations(sources: Map<String, String>): List<String> =
      sources
          .flatMap { (path, source) ->
            if (inside(source)) emptyList()
            else names(source).filterNot { it in ALLOWED }.map { "$path -> $it: ${why(it)}" }
          }
          .distinct()

  private fun inside(source: String): Boolean {
    val declared = PACKAGE.find(source)?.groupValues?.get(1) ?: return false
    return declared == PACKAGE_NAME || declared.startsWith("$PACKAGE_NAME.")
  }

  /** The sync names [source] imports or spells out in full. */
  private fun names(source: String): List<String> =
      source
          .lineSequence()
          .flatMap { line ->
            val imported = IMPORT.find(line)?.groupValues?.get(1)
            when {
              line.trimStart().startsWith("package ") -> emptySequence()
              imported != null -> sequenceOf(imported)
              else -> REFERENCE.findAll(line).map { it.groupValues[1] }.asSequence()
            }
          }
          .toList()

  private fun why(name: String): String =
      if (name == "*") "nothing outside `sync` may import the package as a whole"
      else "nothing outside `sync` may use it; add it to SyncSurface.ALLOWED on purpose"
}
