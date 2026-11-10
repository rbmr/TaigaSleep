package com.rbmr.taigasleep.recording

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingExportTest {
  private val root = Files.createTempDirectory("taiga-export").toFile()
  private val dir = File(root, "night").apply { mkdirs() }
  private val raw = ByteArray(200_000) { (it % 251).toByte() }

  @After
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun night() {
    File(dir, "hr.log").writeBytes(raw)
    File(dir, "ecg.log").writeBytes(ByteArray(1_000))
    File(dir, "meta.json").writeText("""{"version":1}""")
    File(dir, "events.jsonl").writeText("""{"event":"start"}""" + "\n")
  }

  private fun export(contents: ExportContents, results: List<ExportResult> = emptyList()): ZipFile {
    val out = File(root, "export-${contents.hashCode()}-${results.size}.zip")
    RecordingExport.write("night", dir, out, contents, results)
    return ZipFile(out)
  }

  private fun ZipFile.names(): List<String> = entries().asSequence().map { it.name }.toList()

  private fun ZipFile.text(name: String): String =
      getInputStream(getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8) }

  @Test
  fun `raw only holds the stream logs and no metadata`() {
    night()
    export(ExportContents(raw = true, metadata = false)).use { zip ->
      assertEquals(listOf("night/ecg.log", "night/hr.log"), zip.names())
    }
  }

  @Test
  fun `metadata only holds the metadata after the raw files were deleted`() {
    night()
    File(dir, "hr.log").delete()
    File(dir, "ecg.log").delete()

    export(ExportContents(raw = false, metadata = true)).use { zip ->
      assertEquals(listOf("night/events.jsonl", "night/meta.json"), zip.names())
      assertEquals("""{"version":1}""", zip.text("night/meta.json"))
    }
  }

  @Test
  fun `the default contents are the raw recording and its metadata, without results`() {
    night()

    export(
            ExportContents(),
            results = listOf(ExportResult("run-1.json", """{"id":"run-1"}""")),
        )
        .use { zip ->
          assertEquals(
              listOf("night/ecg.log", "night/events.jsonl", "night/hr.log", "night/meta.json"),
              zip.names(),
          )
        }
  }

  @Test
  fun `everything adds one file per saved analysis result`() {
    night()

    export(
            ExportContents(raw = true, metadata = true, results = true),
            results =
                listOf(
                    ExportResult("run-1.json", """{"id":"run-1"}"""),
                    ExportResult("run-2.json", """{"id":"run-2"}"""),
                ),
        )
        .use { zip ->
          assertEquals(
              listOf(
                  "night/analysis/run-1.json",
                  "night/analysis/run-2.json",
                  "night/ecg.log",
                  "night/events.jsonl",
                  "night/hr.log",
                  "night/meta.json",
              ),
              zip.names().sorted(),
          )
          assertEquals("""{"id":"run-1"}""", zip.text("night/analysis/run-1.json"))
        }
  }

  @Test
  fun `metadata and results without raw files make a valid archive`() {
    night()
    File(dir, "hr.log").delete()
    File(dir, "ecg.log").delete()

    export(
            ExportContents(raw = false, metadata = true, results = true),
            results = listOf(ExportResult("run-1.json", """{"id":"run-1"}""")),
        )
        .use { zip ->
          assertEquals(
              listOf("night/analysis/run-1.json", "night/events.jsonl", "night/meta.json"),
              zip.names().sorted(),
          )
        }
  }

  @Test
  fun `stream logs are stored and text is deflated`() {
    night()

    export(ExportContents(raw = true, metadata = true)).use { zip ->
      assertEquals(ZipEntry.STORED, zip.getEntry("night/hr.log").method)
      assertEquals(ZipEntry.DEFLATED, zip.getEntry("night/meta.json").method)
      assertEquals(200_000L, zip.getEntry("night/hr.log").size)
      assertTrue(zip.getInputStream(zip.getEntry("night/hr.log")).use { it.readBytes() }.contentEquals(raw))
    }
  }

  @Test
  fun `progress counts the bytes of the chosen contents and ends complete`() {
    night()
    val seen = mutableListOf<ExportProgress>()
    val out = File(root, "progress.zip")

    RecordingExport.write(
        id = "night",
        dir = dir,
        out = out,
        contents = ExportContents(raw = true, metadata = true),
        results = listOf(ExportResult("run-1.json", "0123456789")),
        onProgress = { seen += it },
    )

    val total =
        200_000L +
            1_000L +
            """{"version":1}""".length +
            ("""{"event":"start"}""" + "\n").length
    assertTrue(seen.isNotEmpty())
    assertTrue(seen.all { it.doneBytes <= it.totalBytes })
    assertEquals(total, seen.last().totalBytes)
    assertEquals(total, seen.last().doneBytes)
    assertEquals(1f, seen.last().fraction, 0.0001f)
    // A result that was not selected is not added to the archive or its total.
    assertFalse(out.length() < total)
  }

  @Test
  fun `a recording directory with nothing left still exports its results`() {
    val out = File(root, "results-only.zip")
    val seen = mutableListOf<ExportProgress>()

    RecordingExport.write(
        id = "night",
        dir = dir,
        out = out,
        contents = ExportContents(raw = false, metadata = false, results = true),
        results = listOf(ExportResult("run-1.json", """{"id":"run-1"}""")),
        onProgress = { seen += it },
    )

    ZipFile(out).use { zip ->
      assertEquals(listOf("night/analysis/run-1.json"), zip.names())
    }
    assertEquals(1f, seen.last().fraction, 0.0001f)
  }

  @Test
  fun `partial writes stay out of every export`() {
    night()
    File(dir, "hr.log.tmp").writeText("half a frame")

    export(ExportContents(raw = true, metadata = true)).use { zip ->
      assertFalse("night/hr.log.tmp" in zip.names())
    }
  }
}
