package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AuthSession
import com.rbmr.taigasleep.backend.AuthUser
import com.rbmr.taigasleep.backend.HttpClients
import com.rbmr.taigasleep.backend.RemoteApi
import com.rbmr.taigasleep.backend.RemoteError
import com.rbmr.taigasleep.backend.RemoteFailure
import com.rbmr.taigasleep.backend.RemotePrefs
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.RemoteStore
import com.rbmr.taigasleep.backend.SecretBox
import com.rbmr.taigasleep.backend.ServerVersion
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.store.RecordingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery action a person triggers on a recording. Its session check runs a live server call,
 * so what a cancelled call does matters: it stops the retry, it is not reported as a failed one.
 */
class UploadCoordinatorTest {
  private val prefs = MemoryPrefs()
  private val box = PlainSecretBox()
  private val clients = HttpClients(OkHttpClient())

  private fun repository(api: RemoteApi): RemoteRepository {
    val store = RemoteStore(prefs, box)
    store.saveSession("http://localhost:8090", AuthSession("token", "me@example.com"), 0)
    return RemoteRepository(store, api, clients)
  }

  private fun retry(api: RemoteApi) =
      runBlocking {
        UploadCoordinator.retryAnalysis(
            remote = repository(api),
            store = UploadStore(UploadlessStorage()),
            key = "r1",
            analysisId = null,
            config = { RecordingConfig(hr = true, ecg = false, acc = false) },
            settings = AnalysisSettings(),
        )
      }

  @Test
  fun `a cancelled session check cancels the retry instead of failing it`() {
    val api = ScriptedRemoteApi { throw CancellationException("cancelled") }

    val thrown = runCatching { retry(api) }.exceptionOrNull()

    assertTrue("the retry reported ${thrown?.javaClass?.name}", thrown is CancellationException)
  }

  @Test
  fun `a session check that fails for another reason is still a failed retry`() {
    val api = ScriptedRemoteApi { throw RemoteFailure(RemoteError.NotTaiga) }

    val result = retry(api)

    assertTrue(result.isFailure)
  }
}

/** A [RemoteApi] whose version query does what the test says; the other calls are not reached. */
private class ScriptedRemoteApi(private val version: () -> ServerVersion) : RemoteApi {
  override fun version(baseUrl: String): ServerVersion = version()

  override fun login(baseUrl: String, email: String, password: String): AuthSession =
      throw UnsupportedOperationException("not used")

  override fun refresh(baseUrl: String, token: String): AuthSession =
      throw UnsupportedOperationException("not used")

  override fun authCheck(baseUrl: String, token: String): AuthUser =
      throw UnsupportedOperationException("not used")
}

private class MemoryPrefs : RemotePrefs {
  private val strings = mutableMapOf<String, String>()
  private val longs = mutableMapOf<String, Long>()

  override fun getString(key: String): String? = strings[key]

  override fun putString(key: String, value: String?) {
    if (value == null) strings.remove(key) else strings[key] = value
  }

  override fun getLong(key: String): Long? = longs[key]

  override fun putLong(key: String, value: Long?) {
    if (value == null) longs.remove(key) else longs[key] = value
  }
}

private class PlainSecretBox : SecretBox {
  override fun seal(plain: ByteArray): ByteArray = plain

  override fun open(blob: ByteArray): ByteArray = blob
}

private class UploadlessStorage : UploadRecordsStorage {
  private val documents = mutableMapOf<String, String>()

  override fun ids(): Set<String> = documents.keys

  override fun read(id: String): String? = documents[id]

  override fun write(id: String, json: String) {
    documents[id] = json
  }

  override fun delete(id: String) {
    documents.remove(id)
  }

  override fun legacy(): String? = null

  override fun clearLegacy() {}
}
