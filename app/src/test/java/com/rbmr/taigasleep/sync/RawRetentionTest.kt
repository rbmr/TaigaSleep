package com.rbmr.taigasleep.sync

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRetentionTest {
  @Test
  fun theDefaultIsToKeepRawData() {
    assertEquals(RawRetentionMode.NEVER, RawRetentionMode.fromStored(null))
    assertEquals(RawRetentionMode.NEVER, RawRetentionMode.fromStored("something-else"))
    assertNull(RawRetentionMode.NEVER.delayMs)
  }

  @Test
  fun everyModeSurvivesTheStoredValue() {
    for (mode in RawRetentionMode.entries) {
      assertEquals(mode, RawRetentionMode.fromStored(mode.storedValue))
    }
  }

  @Test
  fun neverIsNeverDue() {
    assertFalse(isRawCleanupDue(RawRetentionMode.NEVER, 0, Long.MAX_VALUE))
  }

  @Test
  fun immediateIsDueAsSoonAsTheUploadIsConfirmed() {
    assertFalse(isRawCleanupDue(RawRetentionMode.IMMEDIATE, 1_000, 999))
    assertTrue(isRawCleanupDue(RawRetentionMode.IMMEDIATE, 1_000, 1_000))
  }

  @Test
  fun dayModesWaitForTheirDelay() {
    val uploaded = 1_000L
    val fiveDays = TimeUnit.DAYS.toMillis(5)
    assertFalse(isRawCleanupDue(RawRetentionMode.DAYS_5, uploaded, uploaded + fiveDays - 1))
    assertTrue(isRawCleanupDue(RawRetentionMode.DAYS_5, uploaded, uploaded + fiveDays))
    assertFalse(isRawCleanupDue(RawRetentionMode.DAYS_14, uploaded, uploaded + fiveDays))
    assertTrue(
        isRawCleanupDue(
            RawRetentionMode.DAYS_30,
            uploaded,
            uploaded + TimeUnit.DAYS.toMillis(30),
        )
    )
  }

  @Test
  fun cleanupNeedsAConfirmedUpload() {
    with(RawRetention) {
      assertNull(UploadRecord(id = "local", phase = UploadPhase.LOCAL).uploadConfirmedAt())
      assertNull(
          UploadRecord(id = "queued", phase = UploadPhase.QUEUED, onServer = true)
              .uploadConfirmedAt()
      )
      assertNull(
          UploadRecord(
                  id = "failed-before-complete",
                  phase = UploadPhase.FAILED,
                  onServer = true,
                  updatedAtMs = 10,
              )
              .uploadConfirmedAt()
      )
    }
  }

  @Test
  fun theConfirmationTimeIsRemembered() {
    with(RawRetention) {
      assertEquals(
          5_000L,
          UploadRecord(
                  id = "processing",
                  phase = UploadPhase.PROCESSING,
                  onServer = true,
                  uploadedAtMs = 5_000,
                  updatedAtMs = 9_000,
              )
              .uploadConfirmedAt(),
      )
      // A failed analysis does not undo the confirmed upload; the raw files are on the server.
      assertEquals(
          5_000L,
          UploadRecord(
                  id = "analysis-failed",
                  phase = UploadPhase.FAILED,
                  onServer = true,
                  uploadedAtMs = 5_000,
                  updatedAtMs = 9_000,
              )
              .uploadConfirmedAt(),
      )
      // Records written before uploadedAtMs existed fall back to their last transition.
      assertEquals(
          9_000L,
          UploadRecord(
                  id = "old",
                  phase = UploadPhase.DONE,
                  onServer = true,
                  updatedAtMs = 9_000,
              )
              .uploadConfirmedAt(),
      )
    }
  }
}
