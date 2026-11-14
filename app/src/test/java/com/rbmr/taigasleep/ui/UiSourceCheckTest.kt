package com.rbmr.taigasleep.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source check of the design foundations: a screen draws with the theme tokens and the
 * component kit, never with a hex colour, a text size in sp or a hand-built corner radius. The
 * check covers every Kotlin file under `ui/` except `ui/theme` and `ui/components`, where those
 * values are allowed.
 */
class UiSourceCheckTest {
  @Test
  fun `ui files outside theme and components spell no colours, text sizes or corner radii`() {
    val files = uiFiles()
    assertTrue("no Kotlin sources found under ${uiDir()}", files.isNotEmpty())

    val violations = files.flatMap { (name, source) -> violations(name, source) }

    assertTrue(
        "Styling belongs to the theme and the component kit (${violations.size}):\n" +
            violations.joinToString("\n") { "  $it" },
        violations.isEmpty(),
    )
  }

  @Test
  fun `the checker reports each forbidden value`() {
    val source =
        """
        package com.rbmr.taigasleep.ui.example

        val color = Color(0xFF123456)
        val text = TextStyle(fontSize = 13.sp)
        val shape = RoundedCornerShape(12.dp)
        """
            .trimIndent()

    val found = violations("example/Example.kt", source)

    assertEquals(3, found.size)
    assertTrue(found[0], "hex colour" in found[0])
    assertTrue(found[1], "text size in sp" in found[1])
    assertTrue(found[2], "corner radius" in found[2])
  }

  @Test
  fun `the checker accepts a file that uses the theme tokens`() {
    val source =
        """
        package com.rbmr.taigasleep.ui.example

        val style = MaterialTheme.taiga.text.row
        val radius = MaterialTheme.taiga.shape.card
        val spacing = MaterialTheme.taiga.space.m
        """
            .trimIndent()

    assertEquals(emptyList<String>(), violations("example/Example.kt", source))
  }

  /** One line per forbidden value, with the file and the line number that spells it. */
  private fun violations(path: String, source: String): List<String> =
      source.lineSequence().mapIndexedNotNull { index, line ->
        val rule =
            FORBIDDEN.firstOrNull { it.pattern.containsMatchIn(line) }
                ?: return@mapIndexedNotNull null
        "$path:${index + 1}: $line -- ${rule.why}"
      }.toList()

  /** Every Kotlin file under `ui/` outside the theme and the kit, keyed by its path in `ui/`. */
  private fun uiFiles(): Map<String, String> {
    val root = uiDir()
    return root
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(root).path to it.readText() }
        .filterNot { (path, _) -> path.substringBefore('/') in ALLOWED_DIRECTORIES }
        .toList()
        .toMap()
  }

  private fun uiDir(): File = File(moduleDir(), "src/main/java/com/rbmr/taigasleep/ui")

  private fun moduleDir(): File =
      listOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }.canonicalFile

  private class Rule(val pattern: Regex, val why: String)

  private companion object {
    /** The two directories that hold the values every other file reads. */
    val ALLOWED_DIRECTORIES = setOf("theme", "components")

    val FORBIDDEN =
        listOf(
            Rule(Regex("""Color\(0x"""), "a hex colour belongs in ui/theme"),
            Rule(Regex("""\b\d+(?:\.\d+)?\.sp\b"""), "a text size in sp belongs in ui/theme"),
            Rule(Regex("""RoundedCornerShape\("""), "a corner radius belongs in ui/theme"),
        )
  }
}
