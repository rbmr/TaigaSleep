package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.tracker.RecordingMode
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
) = SavedChoices(hr, ecg, acc, mode, wake)

private fun resolve(saved: SavedChoices, modes: Set<RecordingMode> = BOTH) =
    StartConfig.resolve(saved, modes, canRecordEcg = true, canRecordAcc = true)

/** The recording mode alone decides what the data and the smart alarm are. */
class StartConfigTest {
  @Test
  fun `streaming uses the stored stream choices`() {
    val config = resolve(saved())

    assertEquals(Streams(hr = true, ecg = true, acc = true), config.streams)
    assertEquals(ALARM, config.wake)
    assertFalse(config.onTracker)
  }

  @Test
  fun `the tracker records heart rate only and has no smart alarm`() {
    val config = resolve(saved(mode = RecordingMode.ON_DEVICE))

    assertEquals(Streams(hr = true, ecg = false, acc = false), config.streams)
    assertNull(config.wake)
  }

  @Test
  fun `switching to the tracker and back restores the stream choices`() {
    val stored = saved(ecg = true, acc = false)

    val onTracker = resolve(stored.copy(mode = RecordingMode.ON_DEVICE))
    val back = resolve(stored.copy(mode = RecordingMode.STREAM))

    assertFalse(onTracker.streams.ecg)
    assertEquals(Streams(hr = true, ecg = true, acc = false), back.streams)
    assertEquals(ALARM, back.wake)
  }

  @Test
  fun `heart rate is shared by both modes`() {
    for (mode in BOTH) {
      assertTrue(resolve(saved(mode = mode, hr = true)).streams.hr)
      assertFalse(resolve(saved(mode = mode, hr = false)).streams.hr)
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
    val config =
        StartConfig.resolve(saved(), BOTH, canRecordEcg = false, canRecordAcc = false)

    assertEquals(Streams(hr = true, ecg = false, acc = false), config.streams)
  }

  @Test
  fun `a stored mode the tracker does not offer falls back to streaming`() {
    val config = resolve(saved(mode = RecordingMode.ON_DEVICE), modes = setOf(RecordingMode.STREAM))

    assertEquals(RecordingMode.STREAM, config.mode)
    assertEquals(ALARM, config.wake)
  }
}
