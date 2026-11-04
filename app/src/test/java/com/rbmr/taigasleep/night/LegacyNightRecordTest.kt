package com.rbmr.taigasleep.night

import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadPhase
import com.rbmr.taigasleep.upload.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old upload record is the persistence adapter for the new domain; its phases must map back. */
class LegacyNightRecordTest {
  @Test
  fun aLocalRecordingHasNoServerCopy() {
    val record = UploadRecord(id = "n", phase = UploadPhase.LOCAL).toNightRecord()

    assertEquals(BackupPhase.NOT_BACKED_UP, record.backup.phase)
    assertTrue(record.analyses.isEmpty())
  }

  @Test
  fun aQueuedUploadKeepsItsReason() {
    val record =
        UploadRecord(
                id = "n",
                phase = UploadPhase.QUEUED,
                failure = UploadFailure.RECORDING,
                errorDetail = "still recording",
            )
            .toNightRecord()

    assertEquals(BackupPhase.QUEUED, record.backup.phase)
    assertEquals(UploadFailure.RECORDING, record.backup.failure)
    assertEquals("still recording", record.backup.detail)
  }

  @Test
  fun anUploadingNightKeepsItsProgress() {
    val record = UploadRecord(id = "n", phase = UploadPhase.UPLOADING, progress = 0.4f).toNightRecord()

    assertEquals(BackupPhase.UPLOADING, record.backup.phase)
    assertEquals(0.4f, record.backup.progress, 0.001f)
  }

  @Test
  fun anUploadedNightWaitingForAnalysisHasNoRunYet() {
    val record =
        UploadRecord(
                id = "n",
                remoteId = "s1",
                phase = UploadPhase.PROCESSING,
                uploadedAtMs = 100,
            )
            .toNightRecord()

    assertEquals(BackupPhase.BACKED_UP, record.backup.phase)
    assertEquals(100L, record.backup.uploadedAtMs)
    assertTrue(record.analyses.isEmpty())
  }

  @Test
  fun aFinishedAnalysisBecomesOneDoneRun() {
    val record =
        UploadRecord(
                id = "n",
                remoteId = "s1",
                analysisId = "a1",
                jobId = "j1",
                phase = UploadPhase.DONE,
                model = "wav2sleep",
                modelVersion = "1.1.0",
                segments = listOf(StageSegmentDto("light", 4)),
                sleepScore = 81.0,
                resultDownloaded = true,
            )
            .toNightRecord()

    assertEquals(BackupPhase.BACKED_UP, record.backup.phase)
    val run = record.analyses.single()
    assertEquals("a1", run.id)
    assertEquals("wav2sleep", run.algorithm)
    assertEquals("1.1.0", run.version)
    assertEquals(AnalysisStatus.DONE, run.status)
    assertEquals(81.0, run.sleepScore ?: 0.0, 0.001)
    assertTrue(run.resultDownloaded)
  }

  @Test
  fun aFailedUploadStaysAFailedCopy() {
    val record =
        UploadRecord(
                id = "n",
                remoteId = "s1",
                phase = UploadPhase.FAILED,
                failure = UploadFailure.NETWORK,
                errorDetail = "offline",
            )
            .toNightRecord()

    assertEquals(BackupPhase.FAILED, record.backup.phase)
    assertEquals(UploadFailure.NETWORK, record.backup.failure)
    assertEquals("offline", record.backup.detail)
    assertTrue(record.analyses.isEmpty())
  }

  @Test
  fun aFailedAnalysisLeavesTheServerCopyIntact() {
    val record =
        UploadRecord(
                id = "n",
                remoteId = "s1",
                analysisId = "a1",
                phase = UploadPhase.FAILED,
                failure = UploadFailure.ANALYSIS,
                errorDetail = "worker failed",
            )
            .toNightRecord()

    assertEquals(BackupPhase.BACKED_UP, record.backup.phase)
    val run = record.analyses.single()
    assertEquals(AnalysisStatus.FAILED, run.status)
    assertEquals("worker failed", run.error)
  }

  @Test
  fun aFailedAnalysisWithoutAnIdIsStillAFailedNight() {
    val record =
        UploadRecord(
                id = "n",
                remoteId = "s1",
                phase = UploadPhase.FAILED,
                failure = UploadFailure.ANALYSIS,
            )
            .toNightRecord()

    assertEquals(BackupPhase.FAILED, record.backup.phase)
    assertTrue(record.analyses.isEmpty())
    assertNull(record.selectedAnalysisId)
  }
}
