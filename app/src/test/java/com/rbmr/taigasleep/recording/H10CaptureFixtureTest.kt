package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.sensor.HrSample
import com.rbmr.taigasleep.sensor.Stamp
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionState
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the sanitised contact timelines from the two manual H10 captures in
 * `src/test/resources/h10`. The capture files are not recordings: they keep only the relative
 * event/contact timing, without the raw signals or device identifiers.
 */
class H10CaptureFixtureTest {
  private val clock = TestClock()
  private val events = mutableListOf<Pair<String, String>>()

  private fun capture(name: String): JSONObject =
      JSONObject(requireNotNull(javaClass.getResource("/h10/$name")) { "$name not found" }.readText())

  private fun meta() =
      SessionMeta(
          sessionId = "fixture",
          state = SessionState.RECORDING,
          startUtcMs = clock.phoneNs / 1_000_000L,
          endUtcMs = null,
          timezone = "UTC",
          appVersion = "test",
          appVersionCode = 1,
          phoneManufacturer = "JVM",
          phoneModel = "test",
          android = "0",
          sdk = 0,
          sensorKind = "polar-h10",
          sensorId = "fixture",
          sensorName = "Polar H10",
          sensorFirmware = null,
          sensorBatteryAtStart = 80,
          config = SessionConfig(hr = true, ecg = false, acc = false),
      )

  private data class Replay(
      val contactEvents: Int,
      val sawOffBody: Boolean,
      val sawRestored: Boolean,
      val offBodyMs: Long,
  )

  /** Feeds one synthetic HR sample per second with the recorded raw contact flag. */
  private fun replay(name: String): Replay {
    events.clear()
    val json = capture(name)
    val transitions = json.getJSONArray("contact")
    val durationMs = (json.getDouble("duration_s") * 1000).toLong()
    val monitor =
        RecordingMonitor(meta(), clock, { type, message -> events += type to message }) {
          StorageHealth()
        }
    var nextTransition = 0
    var rawContact: Boolean? = null
    var sawOffBody = false
    var sawRestored = false
    var offBodyMs = 0L
    var elapsedMs = 0L
    while (elapsedMs <= durationMs) {
      while (nextTransition < transitions.length()) {
        val transition = transitions.getJSONObject(nextTransition)
        if (transition.getDouble("t") * 1000 > elapsedMs) break
        rawContact = transition.getBoolean("contact")
        nextTransition++
      }
      monitor.onHr(
          Stamp.now(clock),
          HrSample(60, contactSupported = true, contact = rawContact ?: true, rrAvailable = false, rrMs = IntArray(0)),
      )
      monitor.tick(clock.elapsedNs())
      val contact = monitor.state.value.contact
      if (contact.state == RecordingState.ContactState.OFF_BODY) {
        sawOffBody = true
        offBodyMs = contact.offBodyMs
      } else if (sawOffBody && contact.state == RecordingState.ContactState.ON_BODY) {
        sawRestored = true
      }
      clock.advanceMs(1_000)
      elapsedMs += 1_000
    }
    return Replay(events.count { it.first == "contact" }, sawOffBody, sawRestored, offBodyMs)
  }

  @Test
  fun bothCapturesReplayTheRecordedContactChanges() {
    val first = replay("capture-a.json")
    val second = replay("capture-b.json")

    // Both captures lost and regained contact several times; the raw changes are always logged.
    assertTrue("first contact events ${first.contactEvents}", first.contactEvents >= 4)
    assertTrue("second contact events ${second.contactEvents}", second.contactEvents >= 4)

    // The second capture stayed off the body for more than a minute, so the live state must
    // commit OFF_BODY and then restore it after the 5 s return window.
    assertTrue("second capture should reach OFF_BODY", second.sawOffBody)
    assertTrue("second capture should return to ON_BODY", second.sawRestored)
    assertTrue("committed off-body time ${second.offBodyMs}", second.offBodyMs >= 40_000)
  }

  @Test
  fun fixturesCoverTheStatesTheRecipeExercises() {
    for (name in listOf("capture-a.json", "capture-b.json")) {
      val json = capture(name)
      val types = buildSet {
        val events = json.getJSONArray("events")
        for (i in 0 until events.length()) add(events.getJSONObject(i).getString("type"))
      }
      assertTrue("$name: $types", types.containsAll(listOf("start", "stream_stalled", "disconnect", "reconnect", "stop")))
      assertTrue("$name has no contact transitions", json.getJSONArray("contact").length() >= 3)
    }
  }
}
