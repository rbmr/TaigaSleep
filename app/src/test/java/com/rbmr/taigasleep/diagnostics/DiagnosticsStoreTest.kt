package com.rbmr.taigasleep.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun dir() = File(tmp.root, "diagnostics")

  @Test
  fun linesAreAppendedAndFlushedImmediately() {
    val store = DiagnosticsStore(dir(), "run1")
    store.append("""{"n":1}""")
    store.append("""{"n":2}""")
    // Read without closing or syncing: a killed process keeps what was written.
    val lines = File(dir(), "diag_run1.jsonl").readLines()
    assertEquals(listOf("""{"n":1}""", """{"n":2}"""), lines)
  }

  @Test
  fun rollsToANewSegmentWhenFileIsFull() {
    val store = DiagnosticsStore(dir(), "run1", maxFileBytes = 100)
    repeat(10) { store.append("x".repeat(40)) }
    val names = DiagnosticsStore.list(dir()).map { it.name }
    assertTrue(names.size > 1)
    assertEquals("diag_run1.jsonl", names.first())
    assertEquals("diag_run1_002.jsonl", names[1])
    assertEquals(10, DiagnosticsStore.list(dir()).sumOf { it.readLines().size })
  }

  @Test
  fun trimDeletesOldestFilesToHonourTotalCapButKeepsCurrent() {
    dir().mkdirs()
    for (i in 1..5) {
      File(dir(), "diag_2026010${i}_000000_1.jsonl").writeText("x".repeat(1000))
    }
    val store = DiagnosticsStore(dir(), "20260201_000000_2", maxTotalBytes = 2600)
    store.append("y".repeat(500))
    store.trim(System.currentTimeMillis())
    val names = DiagnosticsStore.list(dir()).map { it.name }
    assertEquals(
        listOf(
            "diag_20260104_000000_1.jsonl",
            "diag_20260105_000000_1.jsonl",
            "diag_20260201_000000_2.jsonl",
        ),
        names,
    )
  }

  @Test
  fun trimDeletesFilesOlderThanMaxAge() {
    dir().mkdirs()
    val old = File(dir(), "diag_old.jsonl").apply { writeText("a") }
    val recent = File(dir(), "diag_recent.jsonl").apply { writeText("b") }
    val now = System.currentTimeMillis()
    old.setLastModified(now - 15L * 24 * 3600 * 1000)
    DiagnosticsStore(dir(), "r").trim(now)
    assertTrue(!old.exists())
    assertTrue(recent.exists())
  }

  @Test
  fun trimIgnoresNonDiagnosticsFiles() {
    dir().mkdirs()
    val marker = File(dir(), "run_marker").apply { writeText("state=running") }
    marker.setLastModified(0)
    DiagnosticsStore(dir(), "r", maxTotalBytes = 1).trim(System.currentTimeMillis())
    assertTrue(marker.exists())
  }

  @Test
  fun unwritableDirectoryDoesNotThrow() {
    val blocker = File(tmp.root, "file").apply { writeText("x") }
    val store = DiagnosticsStore(File(blocker, "sub"), "r")
    store.append("line")
    assertEquals(1, store.failures)
  }
}
