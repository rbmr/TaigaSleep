package com.rbmr.taigasleep

import android.app.Application
import android.content.Context
import com.rbmr.taigasleep.backend.HttpClients
import com.rbmr.taigasleep.backend.OkHttpRemoteApi
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.remoteStore
import com.rbmr.taigasleep.insights.AnalysisSettingsStore
import com.rbmr.taigasleep.library.RecordingLibrary
import com.rbmr.taigasleep.recording.SensorHub
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.AppSyncEnvironment
import com.rbmr.taigasleep.sync.LiveSync
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.PrefsPendingDeleteStorage
import com.rbmr.taigasleep.sync.RecordingDirectoryStorage
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.SyncService
import com.rbmr.taigasleep.sync.UploadScheduler
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.db.LegacyFilesMigration
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import java.io.File
import okhttp3.OkHttpClient

/**
 * The app's long-lived collaborators, built once with their real dependencies and shared by every
 * caller. [TaigaApplication] owns the one instance; other packages reach it through
 * [Context.appContainer] and never construct these classes themselves.
 */
class AppContainer(private val application: Application) {
  val recordingStore: RecordingStore by lazy {
    val base = application.getExternalFilesDir(null) ?: application.filesDir
    RecordingStore(File(base, "recordings"))
  }

  val uploadStore: UploadStore by lazy {
    UploadStore(
        RecordingDirectoryStorage(
            recordingStore.root,
            application.getSharedPreferences("uploads", Context.MODE_PRIVATE),
        ),
        account = { remoteRepository.accountKey },
    )
  }

  /**
   * The one HTTP client of the app. [HttpClients] derives the per-server clients from it with
   * `newBuilder()`, so every call shares its connection pool and dispatcher.
   */
  private val httpClient: OkHttpClient by lazy { OkHttpClient() }

  private val httpClients: HttpClients by lazy { HttpClients(httpClient) }

  val remoteRepository: RemoteRepository by lazy {
    RemoteRepository(
        store = remoteStore(application),
        api = OkHttpRemoteApi(client = httpClients.remote),
        clients = httpClients,
        // Work queued for an account must not outlive it; a new account starts from its own queue.
        onAccountEnded = { UploadScheduler.cancelAllWork(application) },
    )
  }

  val pendingDeleteStore: PendingDeleteStore by lazy {
    PendingDeleteStore(
        PrefsPendingDeleteStorage(
            application.getSharedPreferences("deletes", Context.MODE_PRIVATE)
        ),
        account = { remoteRepository.accountKey },
    )
  }

  val resultCache: ResultCache by lazy { ResultCache(File(application.filesDir, "analysis")) }

  val remoteHistoryCache: RemoteHistoryCache by lazy {
    // The list of an older app holds recording summaries this version cannot read.
    File(application.filesDir, "history/remote-sessions.json").delete()
    RemoteHistoryCache(
        File(application.filesDir, "history/remote-recordings.json"),
        account = { remoteRepository.accountKey },
    )
  }

  val analysisSettingsStore: AnalysisSettingsStore by lazy {
    AnalysisSettingsStore(application)
  }

  /** Everything the app learned from the server, in one database. */
  val database: TaigaDatabase by lazy { TaigaDatabase.open(application) }

  /**
   * The one-time move of the state that older versions kept in files into [database]. It reads the
   * files of the same places the stores below read and leaves them untouched.
   */
  val legacyFilesMigration: LegacyFilesMigration by lazy {
    LegacyFilesMigration(
        recordingsRoot = recordingStore.root,
        uploadPreferences = application.getSharedPreferences("uploads", Context.MODE_PRIVATE),
        historyFile = File(application.filesDir, "history/remote-recordings.json"),
        analysisRoot = File(application.filesDir, "analysis"),
        deletePreferences = application.getSharedPreferences("deletes", Context.MODE_PRIVATE),
        account = { remoteRepository.accountKey },
        legacyAccount = { remoteRepository.legacyAccountKey },
    )
  }

  val recordingLibrary: RecordingLibrary by lazy { RecordingLibrary(application) }

  val syncService: SyncService by lazy { SyncService(AppSyncEnvironment(application)) }

  val sensorHub: SensorHub by lazy { SensorHub() }

  val liveSync: LiveSync by lazy {
    LiveSync(
        gate = remoteRepository.streamAccount,
        events = remoteRepository.events(),
        target = recordingLibrary,
    )
  }
}

/** The process-wide container, reached from any Context through the application object. */
val Context.appContainer: AppContainer
  get() = (applicationContext as TaigaApplication).container
