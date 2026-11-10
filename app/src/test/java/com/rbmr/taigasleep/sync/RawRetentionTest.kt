package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawRetentionTest {
  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope
  private val accounts = MutableStateFlow<String?>("https://sleep.example|u1")

  @Before
  fun setUp() {
    database = testDatabase()
    scope = storeScope()
  }

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
  }

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

  @Test
  fun aRecordingThatReachedTheEndWithoutTheServersWordIsNeverTrimmed() {
    // The old behaviour recorded the upload as confirmed as the last byte went out, so a record
    // like this says only that the transfer got to its end.
    val interrupted =
        UploadRecord(
            id = "interrupted",
            onServer = true,
            phase = UploadPhase.UPLOADING,
            progress = 1f,
            uploadedAtMs = 1_000,
            updatedAtMs = 2_000,
        )
    val failed =
        UploadRecord(
            id = "failed",
            onServer = true,
            phase = UploadPhase.FAILED,
            failure = UploadFailure.NETWORK,
            progress = 1f,
            uploadedAtMs = 1_000,
            updatedAtMs = 2_000,
        )

    assertNull(interrupted.uploadConfirmedAt())
    assertNull(failed.uploadConfirmedAt())
  }

  @Test
  fun `raw cleanup only counts a confirmation from the account it was made on`() {
    // The second account never received these bytes, so its state cannot allow a trim.
    val store = UploadStore(database.uploadStates(), accounts, scope)
    val confirmed =
        runBlocking {
          store.markUploadConfirmed("r1")
          store.record("r1")!!.uploadConfirmedAt()
        }
    assertTrue(confirmed != null)
    assertTrue(isRawCleanupDue(RawRetentionMode.IMMEDIATE, confirmed!!, Long.MAX_VALUE))

    accounts.value = "https://sleep.example|u2"

    assertNull(runBlocking { store.record("r1") }?.uploadConfirmedAt())
  }

  @Test
  fun theConfirmationTimeIsRemembered() {
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
                failure = UploadFailure.ANALYSIS,
                uploadedAtMs = 5_000,
                updatedAtMs = 9_000,
                confirmedOnServer = true,
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
