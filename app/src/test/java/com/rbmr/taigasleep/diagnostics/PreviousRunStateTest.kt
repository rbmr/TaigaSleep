package com.rbmr.taigasleep.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviousRunStateTest {
  @get:Rule val tmp = TemporaryFolder()

  private val heartbeat =
      """{"t_ms":1000,"elapsed_ms":50,"pid":7,"type":"diag_heartbeat","uptime_s":60,""" +
          """"bucket":"ACTIVE","whitelisted":false,"charging":true,"battery_pct":80,"plugged":"usb",""" +
          """"importance":"CACHED","java_heap_kb":5}"""

  @Test
  fun fieldsOfReadsTopLevelValues() {
    val got = PreviousRun.fieldsOf(heartbeat, listOf("bucket", "whitelisted", "battery_pct", "nope"))
    assertEquals(mapOf("bucket" to "ACTIVE", "whitelisted" to "false", "battery_pct" to "80"), got)
  }

  @Test
  fun scanKeepsTheLastHeartbeatState() {
    File(tmp.root, "diag_20260101_000000_7.jsonl")
        .writeText(
            heartbeat + "\n" +
                """{"t_ms":2000,"elapsed_ms":60,"pid":7,"type":"screen_off","locked":true}""" + "\n"
        )
    val run = PreviousRun.scan(tmp.root)
    assertEquals(1000L, run.lastHeartbeatMs)
    assertEquals("screen_off", run.lastType)
    assertEquals("ACTIVE", run.lastState["bucket"])
    assertEquals("usb", run.lastState["plugged"])
  }
}
