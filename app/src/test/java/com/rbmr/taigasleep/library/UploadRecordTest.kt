package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync record says where the bytes are and whether they are moving, nothing about results. */
class UploadRecordTest {
  @Test
  fun aLocalRecordingHasNoServerCopy() {
    val record = UploadRecord(id = "n", phase = UploadPhase.LOCAL)

    assertEquals(BackupPhase.NOT_BACKED_UP, record.backupState().phase)
    assertFalse(record.hasServerCopy())
  }

  @Test
  fun anUploadStartedByThisAppMarksTheServerCopy() {
    val record = UploadRecord(id = "n", onServer = true, phase = UploadPhase.UPLOADING)

    assertTrue(record.hasServerCopy())
  }

  @Test
  fun aRecordFromBeforeTheSharedIdStillKnowsItsRecordingWasUploaded() {
    // Older records have no flag; the phases after a finished upload imply a server copy.
    for (phase in listOf(UploadPhase.PROCESSING, UploadPhase.DONE)) {
      assertTrue(UploadRecord(id = "n", phase = phase).hasServerCopy())
    }
    assertFalse(UploadRecord(id = "n", phase = UploadPhase.QUEUED).hasServerCopy())
  }

  @Test
  fun aQueuedUploadKeepsItsReason() {
    val backup =
        UploadRecord(
                id = "n",
                phase = UploadPhase.QUEUED,
                failure = UploadFailure.RECORDING,
                errorDetail = "still recording",
            )
            .backupState()

    assertEquals(BackupPhase.QUEUED, backup.phase)
    assertEquals(UploadFailure.RECORDING, backup.failure)
    assertEquals("still recording", backup.detail)
  }

  @Test
  fun anUploadingRecordingKeepsItsProgress() {
    val backup = UploadRecord(id = "n", phase = UploadPhase.UPLOADING, progress = 0.4f).backupState()

    assertEquals(BackupPhase.UPLOADING, backup.phase)
    assertEquals(0.4f, backup.progress, 0.001f)
  }

  @Test
  fun anUploadedRecordingWaitingForAnalysisIsABackedUpCopy() {
    val record = UploadRecord(id = "n", phase = UploadPhase.PROCESSING, uploadedAtMs = 100)

    assertEquals(BackupPhase.BACKED_UP, record.backupState().phase)
    assertEquals(100L, record.backupState().uploadedAtMs)
  }

  @Test
  fun aFailedUploadStaysAFailedCopy() {
    val backup =
        UploadRecord(
                id = "n",
                onServer = true,
                phase = UploadPhase.FAILED,
                failure = UploadFailure.NETWORK,
                errorDetail = "offline",
            )
            .backupState()

    assertEquals(BackupPhase.FAILED, backup.phase)
    assertEquals(UploadFailure.NETWORK, backup.failure)
    assertEquals("offline", backup.detail)
  }

  @Test
  fun aFailedAnalysisLeavesTheServerCopyIntact() {
    val record =
        UploadRecord(
            id = "n",
            phase = UploadPhase.FAILED,
            failure = UploadFailure.ANALYSIS,
            errorDetail = "worker failed",
        )

    // The bytes are on the server and only the analysis failed; the run says the rest.
    assertEquals(BackupPhase.BACKED_UP, record.backupState().phase)
  }
}
