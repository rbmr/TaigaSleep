package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val BOTH = setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE)
private val ALARM = WakeChoice(startMinute = 400, lengthMinutes = 20, heartRate = true, movement = true)

private fun saved(
    mode: RecordingMode = RecordingMode.STREAM,
    hr: Boolean = true,
    ecg: Boolean = true,
    acc: Boolean = true,
    wake: WakeChoice? = ALARM,
) = SavedChoices(buildSet {
  if (hr) add("hr")
  if (ecg) add("ecg")
  if (acc) add("acc")
}, mode, wake)

private fun offered(ecg: Boolean = true, acc: Boolean = true): List<StreamOption> =
    buildList {
      add(StreamOption("hr", StreamType.HEART_RATE))
      add(StreamOption("rr", StreamType.RR_INTERVAL))
      if (ecg) add(StreamOption("ecg", StreamType.ECG))
      if (acc) add(StreamOption("acc", StreamType.ACCELEROMETER))
    }

private fun resolve(
    saved: SavedChoices,
    modes: Set<RecordingMode> = BOTH,
    ecg: Boolean = true,
    acc: Boolean = true,
) = StartConfig.resolve(saved, offered(ecg, acc), modes)

/** The recording mode alone decides what the data and the smart alarm are. */
class StartConfigTest {
  @Test
  fun `streaming uses the stored stream choices`() {
    val config = resolve(saved())

    assertEquals(Streams(setOf("hr", "ecg", "acc")), config.streams)
    assertEquals(ALARM, config.wake)
    assertFalse(config.onTracker)
  }

  @Test
  fun `the tracker records heart rate only and has no smart alarm`() {
    val config = resolve(saved(mode = RecordingMode.ON_DEVICE))

    assertEquals(Streams(setOf("hr")), config.streams)
    assertNull(config.wake)
  }

  @Test
  fun `switching to the tracker and back restores the stream choices`() {
    val stored = saved(ecg = true, acc = false)

    val onTracker = resolve(stored.copy(mode = RecordingMode.ON_DEVICE))
    val back = resolve(stored.copy(mode = RecordingMode.STREAM))

    assertFalse("ecg" in onTracker.streams.chosen)
    assertEquals(Streams(setOf("hr", "ecg")), back.streams)
    assertEquals(ALARM, back.wake)
  }

  @Test
  fun `heart rate is shared by both modes`() {
    for (mode in BOTH) {
      assertTrue("hr" in resolve(saved(mode = mode, hr = true)).streams.chosen)
      assertFalse("hr" in resolve(saved(mode = mode, hr = false)).streams.chosen)
    }
  }

  @Test
  fun `both modes stay available whatever data is chosen`() {
    val config = resolve(saved(ecg = true, acc = true))

    assertEquals(BOTH, config.modes)
  }

  @Test
  fun `the tracker with heart rate off has nothing to record`() {
    val config = resolve(saved(mode = RecordingMode.ON_DEVICE, hr = false))

    assertFalse(config.canStart)
  }

  @Test
  fun `data the tracker cannot record stays off`() {
    val config = resolve(saved(), BOTH, ecg = false, acc = false)

    assertEquals(Streams(setOf("hr")), config.streams)
  }

  @Test
  fun `a heart rate service strap offers only heart rate and beat intervals`() {
    val config = StartConfig.resolve(saved(), offered(ecg = false, acc = false), BOTH)

    assertEquals(listOf("hr", "rr"), config.options.map { it.id })
    assertEquals(Streams(setOf("hr")), config.streams)
    assertFalse(config.canRecordAcc)
  }

  @Test
  fun `a stored mode the tracker does not offer falls back to streaming`() {
    val config = resolve(saved(mode = RecordingMode.ON_DEVICE), modes = setOf(RecordingMode.STREAM))

    assertEquals(RecordingMode.STREAM, config.mode)
    assertEquals(ALARM, config.wake)
  }

  @Test
  fun `a stream is found by its type`() {
    val config = resolve(saved(hr = false, ecg = false, acc = true))

    assertEquals(listOf("acc"), config.chosenStreams.map { it.id })
    assertTrue(config.records(StreamType.ACCELEROMETER))
    assertFalse(config.records(StreamType.HEART_RATE))
  }
}
