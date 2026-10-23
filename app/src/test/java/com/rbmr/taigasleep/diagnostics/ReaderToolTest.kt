package com.rbmr.taigasleep.diagnostics

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Writes a sample directory with the real encoder and checks tools/read_diagnostics.py output. */
class ReaderToolTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun python(vararg args: String): String? =
      try {
        val p = ProcessBuilder("python3", "-I", *args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) null else text
      } catch (_: Exception) {
        null
      }

  @Test
  fun printsStartsKillsGapsCrashesAndSettingChanges() {
    val script = File("../tools/read_diagnostics.py").canonicalFile
    assumeTrue(script.exists() && python("-c", "pass") != null)
    val dir = File(tmp.root, "diagnostics").apply { mkdirs() }
    val t0 = 1_800_000_000_000L
    fun line(dt: Long, pid: Int, type: String, fields: Map<String, Any?> = emptyMap()) =
        JsonLine.build(t0 + dt * 1000, dt * 1000, pid, type, fields)

    fun snapshot(dt: Long, pid: Int, bucket: String, whitelisted: Boolean) =
        line(
            dt,
            pid,
            "settings_snapshot",
            mapOf(
                "reason" to "test",
                "battery" to mapOf("level_pct" to 80 - dt / 100),
                "app" to mapOf("standby_bucket_name" to bucket),
                "power" to mapOf("ignoring_battery_optimizations" to whitelisted),
            ),
        )

    File(dir, "diag_a_100.jsonl")
        .writeText(
            listOf(
                    line(0, 100, "process_start", mapOf(
                        "version_name" to "0.1.0",
                        "device" to mapOf("manufacturer" to "OnePlus", "model" to "CPH2769"),
                        "previous_run" to mapOf("verdict" to "first_run"))),
                    snapshot(1, 100, "EXEMPTED", true),
                    line(60, 100, "diag_heartbeat"),
                    line(120, 100, "diag_heartbeat"),
                    snapshot(130, 100, "ACTIVE", false),
                    line(180, 100, "screen_off"),
                )
                .joinToString("\n") + "\n"
        )
    File(dir, "diag_b_200.jsonl")
        .writeText(
            listOf(
                    line(900, 200, "process_start", mapOf(
                        "previous_run" to mapOf(
                            "verdict" to "unclean_exit",
                            "seconds_since_last_heartbeat" to 780))),
                    line(901, 200, "exit_info", mapOf(
                        "event_ms" to t0 + 330_000,
                        "reason" to "OTHER",
                        "reason_code" to 13,
                        "description" to "o-kill(6)")),
                    line(905, 200, "diag_heartbeat"),
                    line(950, 200, "crash", mapOf(
                        "thread" to "main",
                        "exception" to "java.lang.RuntimeException",
                        "message" to "boom",
                        "stack" to "java.lang.RuntimeException: boom\n\tat A.b(A.java:1)")),
                )
                .joinToString("\n") + "\n" + """{"t_ms":1800000960000,"elapsed_ms":96"""
        )

    val out = python(script.path, dir.path)
    assertTrue(out, out != null)
    assertTrue(out, "START    pid=100" in out!!)
    assertTrue(out, "previous run: unclean_exit, last heartbeat 13m00s" in out)
    assertTrue(out, "KILLED   OTHER (code 13)" in out && "o-kill(6)" in out)
    assertTrue(out, "GAP      no heartbeat for 13m05s (pid 100 -> 200)" in out)
    assertTrue(out, "suspected kill" in out)
    assertTrue(out, "CRASH    pid=200 thread=main java.lang.RuntimeException: boom" in out)
    assertTrue(out, "app.standby_bucket_name: EXEMPTED -> ACTIVE" in out)
    assertTrue(out, "power.ignoring_battery_optimizations: True -> False" in out)
    assertTrue(out, "battery.level_pct" !in out)
    assertTrue(out, "1 unparsable line(s) skipped" in out)
  }
}
