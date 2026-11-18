package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AuthSession
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.HttpClients
import com.rbmr.taigasleep.backend.RemoteApi
import com.rbmr.taigasleep.backend.RemoteError
import com.rbmr.taigasleep.backend.RemoteFailure
import com.rbmr.taigasleep.backend.RemotePrefs
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.RemoteStore
import com.rbmr.taigasleep.backend.SecretBox
import com.rbmr.taigasleep.backend.ServerVersion
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.insights.AnalysisSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recovery action a person triggers on a recording. Its session check runs a live server call,
 * so what a cancelled call does matters: it stops the retry, it is not reported as a failed one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadCoordinatorTest {
  private val prefs = MemoryPrefs()
  private val box = PlainSecretBox()
  private val clients = HttpClients(OkHttpClient())

  private fun repository(api: RemoteApi): RemoteRepository {
    val store = RemoteStore(prefs, box)
    store.saveSession("http://localhost:8090", AuthSession("token", "me@example.com", "u1"), 0)
    return RemoteRepository(store, api, clients)
  }

  private fun retry(api: RemoteApi) =
      runBlocking {
        UploadCoordinator.retryAnalysis(
            remote = repository(api),
            requests = AnalysisRequester(UnusedEnvironment),
            key = "r1",
            analysisId = null,
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

/** The session check decides before any request is sent, so nothing is asked of this environment. */
private object UnusedEnvironment : AnalysisRequestEnvironment {
  override fun localMeta(key: String) = error("not used")

  override suspend fun serverMeta(api: TaigaClient, recordingId: String) = error("not used")

  override suspend fun serverStart(recordingId: String) = error("not used")

  override fun personalData() = error("not used")

  override suspend fun markProcessing(key: String, account: String) = error("not used")

  override fun retriesUsed(recordingId: String, analysis: AnalysisDto) = error("not used")

  override fun countRetry(recordingId: String, analysis: AnalysisDto) = error("not used")
}

/** A [RemoteApi] whose version query does what the test says; the other calls are not reached. */
private class ScriptedRemoteApi(private val version: () -> ServerVersion) : RemoteApi {
  override fun version(baseUrl: String): ServerVersion = version()

  override fun login(baseUrl: String, email: String, password: String): AuthSession =
      throw UnsupportedOperationException("not used")

  override fun refresh(baseUrl: String, token: String): AuthSession =
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
