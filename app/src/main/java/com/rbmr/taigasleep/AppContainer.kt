package com.rbmr.taigasleep

import android.app.Application
import android.content.Context
import android.os.Looper
import com.rbmr.taigasleep.backend.HttpClients
import com.rbmr.taigasleep.backend.OkHttpRemoteApi
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.remoteStore
import com.rbmr.taigasleep.insights.AnalysisSettingsStore
import com.rbmr.taigasleep.library.RecordingLibrary
import com.rbmr.taigasleep.profile.PersonalDataStore
import com.rbmr.taigasleep.recording.SensorHub
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.AppSyncEnvironment
import com.rbmr.taigasleep.sync.LiveSync
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.RecordingRows
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.SyncService
import com.rbmr.taigasleep.sync.UploadScheduler
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * The app's long-lived collaborators, built once with their real dependencies and shared by every
 * caller. [TaigaApplication] owns the one instance; other packages reach it through
 * [Context.appContainer] and never construct these classes themselves.
 *
 * A collaborator whose constructor opens the database, reads preferences or decrypts the token is
 * built through [offMain]: the first caller may be a screen on the main thread, and starting the
 * app is not the place to pay for a Keystore call or a directory scan.
 */
class AppContainer(private val application: Application) {
  /** Long-lived work of the wiring itself: what the stores publish. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val recordingStore: RecordingStore by offMain {
    val base = application.getExternalFilesDir(null) ?: application.filesDir
    RecordingStore(File(base, "recordings"))
  }

  /** Everything the app learned from the server, in one database. */
  val database: TaigaDatabase by offMain { TaigaDatabase.open(application) }

  /** The sync state of every recording, for the account that is active. */
  val uploadStore: UploadStore by offMain {
    UploadStore(database.uploadStates(), remoteRepository.accounts, scope)
  }

  /**
   * The one HTTP client of the app. [HttpClients] derives the per-server clients from it with
   * `newBuilder()`, so every call shares its connection pool and dispatcher.
   */
  private val httpClient: OkHttpClient by lazy { OkHttpClient() }

  private val httpClients: HttpClients by lazy { HttpClients(httpClient) }

  /** The remote stack; its constructor reads the saved session and opens the token. */
  val remoteRepository: RemoteRepository by offMain {
    RemoteRepository(
        store = remoteStore(application),
        api = OkHttpRemoteApi(client = httpClients.remote),
        clients = httpClients,
        // Work queued for an account must not outlive it; a new account starts from its own queue.
        onAccountEnded = { UploadScheduler.cancelAllWork(application) },
    )
  }

  val pendingDeleteStore: PendingDeleteStore by offMain {
    PendingDeleteStore(database.pendingDeletes(), remoteRepository.accounts, scope)
  }

  val resultCache: ResultCache by offMain { ResultCache(database.analysisResults()) }

  /**
   * The one read of the library: every fact the screens show about a recording, in one query joined
   * with the recordings on this phone. Every store that writes a row publishes through it.
   */
  val recordingRows: RecordingRows by offMain { RecordingRows(database.library()) }

  val remoteHistoryCache: RemoteHistoryCache by offMain {
    RemoteHistoryCache(database.remoteRecordings())
  }

  val analysisSettingsStore: AnalysisSettingsStore by offMain {
    AnalysisSettingsStore(application)
  }

  /** The personal data the user entered, kept on the phone and read when SleepECG is requested. */
  val personalDataStore: PersonalDataStore by offMain { PersonalDataStore(application) }

  val recordingLibrary: RecordingLibrary by offMain { RecordingLibrary(application) }

  val syncService: SyncService by offMain { SyncService(AppSyncEnvironment(application), scope) }

  val sensorHub: SensorHub by lazy { SensorHub() }

  val liveSync: LiveSync by offMain {
    LiveSync(
        gate = remoteRepository.streamAccount,
        events = remoteRepository.events(),
        target = recordingLibrary,
    )
  }

  /**
   * A collaborator built on a background thread when the main thread asks for it first. A caller on
   * any other thread builds it in place, so nothing pays for a thread hand-off it does not need.
   */
  private fun <T> offMain(build: () -> T): Lazy<T> =
      lazy {
        if (Looper.myLooper() == Looper.getMainLooper()) {
          runBlocking(Dispatchers.IO) { build() }
        } else {
          build()
        }
      }
}

/** The process-wide container, reached from any Context through the application object. */
val Context.appContainer: AppContainer
  get() = (applicationContext as TaigaApplication).container
