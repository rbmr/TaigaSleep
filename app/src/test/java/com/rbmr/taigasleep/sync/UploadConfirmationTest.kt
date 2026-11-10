package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.UploadStateRow
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import java.io.IOException
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

/** A server that answers the one question the confirmation check asks. */
private class ServerClient(
    private val complete: Boolean = true,
    private val error: Exception? = null,
) : StubTaigaClient() {
  var asked = 0

  override suspend fun getRecording(id: String): RecordingDto {
    asked++
    error?.let { throw it }
    return RecordingDto(
        id = id,
        state = if (complete) "stopped" else "recording",
        complete = complete,
    )
  }
}

private const val ACCOUNT = "https://sleep.example|u1"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadConfirmationTest {
  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope
  private val accounts = MutableStateFlow<String?>(ACCOUNT)

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

  /**
   * What the old behaviour left behind: a confirmation time written as the last byte went out, and
   * an attempt that failed without hearing the server accept the recording.
   */
  private fun legacyStore(): UploadStore {
    runBlocking {
      database
          .uploadStates()
          .upsert(
              UploadStateRow(
                  account = ACCOUNT,
                  recordingId = "r1",
                  onServer = true,
                  phase = UploadPhase.FAILED,
                  progress = 1f,
                  failure = UploadFailure.NETWORK,
                  errorDetail = "connection lost",
                  updatedAtMs = 2000,
                  uploadedAtMs = 1000,
              )
          )
    }
    return UploadStore(database.uploadStates(), accounts, scope)
  }

  @Test
  fun onlyARecordLeftAtTheEndWithoutTheServersWordNeedsTheCheck() {
    val legacy = UploadRecord(id = "r1", phase = UploadPhase.UPLOADING, progress = 1f)
    assertTrue(legacy.awaitsUploadConfirmation())
    assertFalse(legacy.copy(progress = 0.4f).awaitsUploadConfirmation())
    assertFalse(legacy.copy(phase = UploadPhase.QUEUED).awaitsUploadConfirmation())
    assertFalse(legacy.copy(confirmedOnServer = true).awaitsUploadConfirmation())
    // The phases the server's answers set are evidence on their own.
    assertFalse(legacy.copy(phase = UploadPhase.PROCESSING).awaitsUploadConfirmation())
  }

  @Test
  fun aRecordingTheServerHasCompleteConfirmsTheLegacyRecord() = runBlocking {
    val store = legacyStore()
    assertNull(store.record("r1")!!.uploadConfirmedAt())
    val client = ServerClient(complete = true)

    val confirmed = confirmUploadOnServer(client, "r1", store, ACCOUNT)

    assertTrue(confirmed)
    assertEquals(1, client.asked)
    assertTrue(store.record("r1")!!.uploadConfirmedAt() != null)
  }

  @Test
  fun aRecordingTheServerHasNotFinishedStaysUnconfirmed() = runBlocking {
    val store = legacyStore()

    val confirmed = confirmUploadOnServer(ServerClient(complete = false), "r1", store, ACCOUNT)

    assertFalse(confirmed)
    assertFalse(store.record("r1")!!.confirmedOnServer)
    assertNull(store.record("r1")!!.uploadConfirmedAt())
  }

  @Test
  fun aQuestionThatCannotBeAskedLeavesTheRecordUnconfirmed() = runBlocking {
    val store = legacyStore()

    val confirmed =
        confirmUploadOnServer(
            ServerClient(error = TaigaNetworkException(IOException("offline"))),
            "r1",
            store,
            ACCOUNT,
        )

    assertFalse(confirmed)
    assertFalse(store.record("r1")!!.confirmedOnServer)
    assertNull(store.record("r1")!!.uploadConfirmedAt())
  }
}
