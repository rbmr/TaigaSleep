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
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
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
  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope

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

  private fun repository(api: RemoteApi): RemoteRepository {
    val store = RemoteStore(prefs, box)
    store.saveSession("http://localhost:8090", AuthSession("token", "me@example.com"), "u1", 0)
    return RemoteRepository(store, api, clients)
  }

  private fun retry(api: RemoteApi) =
      runBlocking {
        UploadCoordinator.retryAnalysis(
            remote = repository(api),
            store =
                UploadStore(
                    database.uploadStates(),
                    MutableStateFlow<String?>("http://localhost:8090|u1"),
                    scope,
                ),
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
