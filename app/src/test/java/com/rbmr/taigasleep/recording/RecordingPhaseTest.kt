package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The states the upload queue, the upload worker and the raw cleanup must leave alone. A recording
 * that can still grow is not sent and not trimmed; a finished one is.
 */
class RecordingPhaseTest {
  @Test
  fun aRecordingWhoseBytesCanStillGrowIsUnfinished() {
    assertTrue(RecordingPhase.RECORDING.unfinished)
    // The rest of the night is still on the tracker: the files grow when it is fetched.
    assertTrue(RecordingPhase.FETCH_PENDING.unfinished)
  }

  @Test
  fun everyFinishedStateIsUploadable() {
    assertEquals(
        listOf(
            RecordingPhase.STOPPED,
            RecordingPhase.FORCED_STOP,
            RecordingPhase.INTERRUPTED,
        ),
        RecordingPhase.entries.filterNot { it.unfinished },
    )
  }

  @Test
  fun theTrackersPendingStateSurvivesTheWireValue() {
    // The server and meta.json name the state; a fetch that is still pending must read back as it.
    assertEquals(RecordingPhase.FETCH_PENDING, RecordingPhase.fromWire("fetch_pending"))
  }
}
