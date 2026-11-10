package com.rbmr.taigasleep.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {
  @Test
  fun `the default export is the raw recording and its metadata`() {
    val contents = ExportContents()

    assertTrue(contents.raw)
    assertTrue(contents.metadata)
    assertFalse(contents.results)
    assertEquals(
        ExportSource.LOCAL,
        exportSource(contents, hasRaw = true, hasMetadata = true, hasResults = true, onServer = true),
    )
  }

  @Test
  fun `raw files on the phone export without the server`() {
    val contents = ExportContents(raw = true, metadata = true)
    assertEquals(
        ExportSource.LOCAL,
        exportSource(contents, hasRaw = true, hasMetadata = true, hasResults = false, onServer = false),
    )
  }

  @Test
  fun `missing raw files are fetched from the server first`() {
    val contents = ExportContents(raw = true, metadata = true)
    assertEquals(
        ExportSource.RESTORE,
        exportSource(contents, hasRaw = false, hasMetadata = true, hasResults = false, onServer = true),
    )
  }

  @Test
  fun `raw files that no copy has cannot be exported`() {
    val contents = ExportContents(raw = true, metadata = true)
    assertEquals(
        ExportSource.UNAVAILABLE,
        exportSource(
            contents,
            hasRaw = false,
            hasMetadata = true,
            hasResults = false,
            onServer = false,
        ),
    )
  }

  @Test
  fun `metadata alone exports from the phone after the raw files were deleted`() {
    val contents = ExportContents(raw = false, metadata = true)

    assertEquals(
        ExportSource.LOCAL,
        exportSource(contents, hasRaw = false, hasMetadata = true, hasResults = false, onServer = true),
    )
    assertEquals(
        ExportSource.LOCAL,
        exportSource(
            contents,
            hasRaw = false,
            hasMetadata = true,
            hasResults = false,
            onServer = false,
        ),
    )
  }

  @Test
  fun `a saved result alone exports without raw files or the server`() {
    val contents = ExportContents(raw = false, metadata = false, results = true)

    assertEquals(
        ExportSource.LOCAL,
        exportSource(contents, hasRaw = false, hasMetadata = false, hasResults = true, onServer = false),
    )
  }

  @Test
  fun `results that are not saved on the phone cannot be exported on their own`() {
    val contents = ExportContents(raw = false, metadata = false, results = true)

    assertEquals(
        ExportSource.UNAVAILABLE,
        exportSource(contents, hasRaw = true, hasMetadata = true, hasResults = false, onServer = true),
    )
  }

  @Test
  fun `nothing selected exports nothing`() {
    val contents = ExportContents(raw = false, metadata = false, results = false)

    assertEquals(
        ExportSource.UNAVAILABLE,
        exportSource(contents, hasRaw = true, hasMetadata = true, hasResults = true, onServer = true),
    )
    assertTrue(contents.isEmpty)
  }

  @Test
  fun `an analysis result is named after its run`() {
    assertEquals("analysis-run-7.json", analysisExportName("run-7"))
  }

  @Test
  fun `a new export removes every archive an earlier one left`() {
    val root = Files.createTempDirectory("taiga-export-dir").toFile()
    val dir = File(root, "export").apply { mkdirs() }
    File(dir, "taiga-old-night.zip").writeText("old")
    File(dir, "taiga-older-night.zip").writeText("older")

    Exporter.clear(dir)

    assertTrue(dir.isDirectory)
    assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
    root.deleteRecursively()
  }

  @Test
  fun `an export from an earlier run is removed only once it is an hour old`() {
    val root = Files.createTempDirectory("taiga-export-stale").toFile()
    val dir = File(root, "export").apply { mkdirs() }
    val now = 1_800_000_000_000L
    val old = File(dir, "taiga-old-night.zip").apply { writeText("old") }
    val recent = File(dir, "taiga-recent-night.zip").apply { writeText("recent") }
    assertTrue(old.setLastModified(now - Exporter.STALE_EXPORT_MS - 1))
    assertTrue(recent.setLastModified(now - 1_000))

    Exporter.removeStale(dir, nowMs = now)

    assertFalse(old.exists())
    assertTrue(recent.exists())
    root.deleteRecursively()
  }
}
