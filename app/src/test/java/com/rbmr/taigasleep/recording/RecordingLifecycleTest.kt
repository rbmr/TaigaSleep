package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.tracker.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLifecycleTest {
  @Test
  fun streamStartKeepsThePhoneAwake() {
    val lifecycle = RecordingLifecycle(RecordingMode.STREAM)

    assertEquals(listOf(LifecycleEffect.AcquireWakeLock), lifecycle.start())
    assertEquals(LifecycleState.RECORDING, lifecycle.state)
  }

  @Test
  fun onDeviceStartDoesNotKeepThePhoneAwake() {
    val lifecycle = RecordingLifecycle(RecordingMode.ON_DEVICE)

    assertTrue(lifecycle.start().isEmpty())
    assertEquals(LifecycleState.RECORDING, lifecycle.state)
  }

  @Test
  fun streamStopClosesTheStreamsAndFinishes() {
    val lifecycle = RecordingLifecycle(RecordingMode.STREAM)
    lifecycle.start()

    assertEquals(
        listOf(LifecycleEffect.StopStreams, LifecycleEffect.Finish(RecordingPhase.STOPPED)),
        lifecycle.stop(),
    )
    assertEquals(LifecycleState.FINISHED, lifecycle.state)
  }

  @Test
  fun streamForceStopFinishesBeforeTheTrackerAnswers() {
    val lifecycle = RecordingLifecycle(RecordingMode.STREAM)
    lifecycle.start()

    assertEquals(
        listOf(
            LifecycleEffect.Finish(RecordingPhase.FORCED_STOP),
            LifecycleEffect.StopStreamsAsync,
        ),
        lifecycle.forceStop(),
    )
    assertEquals(LifecycleState.FINISHED, lifecycle.state)
  }

  @Test
  fun streamDiscardStopsWithoutDownloadingAndDeletesLocal() {
    val lifecycle = RecordingLifecycle(RecordingMode.STREAM)
    lifecycle.start()

    assertEquals(
        listOf(LifecycleEffect.StopStreamsAsync, LifecycleEffect.DeleteLocal),
        lifecycle.discard(),
    )
    assertEquals(LifecycleState.DISCARDED, lifecycle.state)
  }

  @Test
  fun onDeviceStopFetchesTheRecordingBeforeFinishing() {
    val lifecycle = RecordingLifecycle(RecordingMode.ON_DEVICE)
    lifecycle.start()

    assertEquals(
        listOf(
            LifecycleEffect.FetchFromTracker,
            LifecycleEffect.StopStreams,
            LifecycleEffect.Finish(RecordingPhase.STOPPED),
        ),
        lifecycle.stop(),
    )
    assertEquals(LifecycleState.FINISHED, lifecycle.state)
  }

  @Test
  fun onDeviceForceStopKeepsThePartialLocalCopyAndDoesNotWaitForTheTracker() {
    val lifecycle = RecordingLifecycle(RecordingMode.ON_DEVICE)
    lifecycle.start()

    assertEquals(
        listOf(LifecycleEffect.Finish(RecordingPhase.FORCED_STOP)),
        lifecycle.forceStop(),
    )
    assertEquals(LifecycleState.FINISHED, lifecycle.state)
  }

  @Test
  fun onDeviceDiscardTellsTheTrackerAndDeletesLocal() {
    val lifecycle = RecordingLifecycle(RecordingMode.ON_DEVICE)
    lifecycle.start()

    assertEquals(
        listOf(LifecycleEffect.DiscardOnTracker, LifecycleEffect.DeleteLocal),
        lifecycle.discard(),
    )
    assertEquals(LifecycleState.DISCARDED, lifecycle.state)
  }

  @Test
  fun anInterruptedRecordingKeepsWhatIsOnDisk() {
    val lifecycle = RecordingLifecycle(RecordingMode.STREAM)
    lifecycle.start()

    assertEquals(
        listOf(LifecycleEffect.Finish(RecordingPhase.INTERRUPTED)),
        lifecycle.interrupted(),
    )
    assertEquals(LifecycleState.FINISHED, lifecycle.state)
  }

  @Test
  fun terminalStatesDoNotChangeAgain() {
    for (mode in RecordingMode.entries) {
      val lifecycle = RecordingLifecycle(mode)
      lifecycle.start()
      lifecycle.stop()

      assertTrue(lifecycle.stop().isEmpty())
      assertTrue(lifecycle.forceStop().isEmpty())
      assertTrue(lifecycle.discard().isEmpty())
      assertTrue(lifecycle.start().isEmpty())
      assertEquals(LifecycleState.FINISHED, lifecycle.state)
    }
  }

  @Test
  fun noActionWorksBeforeStart() {
    for (mode in RecordingMode.entries) {
      val lifecycle = RecordingLifecycle(mode)

      assertTrue(lifecycle.stop().isEmpty())
      assertTrue(lifecycle.forceStop().isEmpty())
      assertTrue(lifecycle.discard().isEmpty())
      assertEquals(LifecycleState.IDLE, lifecycle.state)
    }
  }
}
