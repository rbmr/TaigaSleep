package com.rbmr.taigasleep.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunStateTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun line(t: Long, elapsed: Long, type: String) = JsonLine.build(t, elapsed, 7, type)

  @Test
  fun exitInfoIsClaimedOnceAndRemembersAcrossRestarts() {
    val file = File(tmp.root, "seen")
    val first = ExitInfoDedupe(file)
    assertTrue(first.claim(1000, 5))
    assertFalse(first.claim(1000, 5))
    assertTrue(first.claim(1000, 6))
    assertTrue(first.claim(2000, 5))
    first.save()
    val second = ExitInfoDedupe(file)
    assertFalse(second.claim(1000, 5))
    assertFalse(second.claim(2000, 5))
    assertTrue(second.claim(3000, 5))
  }

  @Test
  fun exitInfoKeepsOnlyTheMostRecentKeys() {
    val file = File(tmp.root, "seen")
    val dedupe = ExitInfoDedupe(file, max = 3)
    for (i in 1..5L) dedupe.claim(i, 1)
    dedupe.save()
    assertEquals(listOf("3:1", "4:1", "5:1"), file.readLines())
  }

  @Test
  fun scanFindsLastHeartbeatAndGap() {
    val dir = File(tmp.root, "d").apply { mkdirs() }
    File(dir, "diag_20261023_010000_7.jsonl")
        .writeText(
            listOf(
                    line(1_000_000, 100_000, "process_start"),
                    line(1_060_000, 160_000, "diag_heartbeat"),
                    line(1_120_000, 220_000, "diag_heartbeat"),
                    line(1_130_000, 230_000, "screen_off"),
                )
                .joinToString("\n") + "\n" + """{"t_ms":1140000,"elapsed_ms":2"""
        )
    val prev = PreviousRun.scan(dir)
    assertEquals("diag_20261023_010000_7.jsonl", prev.file)
    assertEquals(7, prev.pid)
    assertEquals("screen_off", prev.lastType)
    assertEquals(1_120_000L, prev.lastHeartbeatMs)
    // 61 minutes after the last heartbeat.
    assertEquals(3660L, prev.secondsSinceLastHeartbeat(1_120_000 + 3_660_000))
    assertEquals(false, prev.rebooted(300_000))
    assertEquals(true, prev.rebooted(1_000))
  }

  @Test
  fun scanLooksBackThroughOlderFilesForAHeartbeat() {
    val dir = File(tmp.root, "d").apply { mkdirs() }
    File(dir, "diag_a.jsonl").writeText(line(500, 50, "diag_heartbeat") + "\n")
    File(dir, "diag_b.jsonl").writeText(line(900, 90, "process_start") + "\n")
    val prev = PreviousRun.scan(dir)
    assertEquals("diag_b.jsonl", prev.file)
    assertEquals("process_start", prev.lastType)
    assertEquals(500L, prev.lastHeartbeatMs)
  }

  @Test
  fun scanOfEmptyOrMissingDirectoryIsEmpty() {
    assertNull(PreviousRun.scan(File(tmp.root, "none")).file)
    val dir = File(tmp.root, "empty").apply { mkdirs() }
    File(dir, "diag_x.jsonl").writeText("")
    val prev = PreviousRun.scan(dir)
    assertNull(prev.file)
    assertNull(prev.secondsSinceLastHeartbeat(1))
  }

  @Test
  fun scanOnlyReadsTheTailOfBigFiles() {
    val dir = File(tmp.root, "d").apply { mkdirs() }
    val body = StringBuilder()
    for (i in 1..5000L) body.append(line(i * 1000, i * 1000, "diag_heartbeat")).append('\n')
    File(dir, "diag_big.jsonl").writeText(body.toString())
    assertEquals(5_000_000L, PreviousRun.scan(dir).lastHeartbeatMs)
  }

  @Test
  fun markerRoundTrip() {
    val marker = RunMarker(File(tmp.root, "sub/run_marker"))
    assertNull(marker.read())
    marker.write("running", 12, 99)
    assertEquals("running", marker.read())
    marker.write("clean", 12, 100)
    assertEquals("clean", marker.read())
  }
}
