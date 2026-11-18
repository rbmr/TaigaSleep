package com.rbmr.taigasleep

import android.app.Application
import android.content.Context
import com.rbmr.taigasleep.backend.HttpClients
import com.rbmr.taigasleep.backend.OkHttpRemoteApi
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.remoteStore
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.insights.AnalysisSettingsStore
import com.rbmr.taigasleep.library.RecordingLibrary
import com.rbmr.taigasleep.profile.PersonalDataStore
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.recording.SensorHub
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.AnalysisRequester
import com.rbmr.taigasleep.sync.AppAnalysisRequestEnvironment
import com.rbmr.taigasleep.sync.AppSyncEnvironment
import com.rbmr.taigasleep.sync.LiveSync
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.RecordingRows
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.SyncService
import com.rbmr.taigasleep.sync.UploadScheduler
import com.rbmr.taigasleep.sync.UploadSettings
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.ui.theme.DisplaySettings
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The app's long-lived collaborators, built once with their real dependencies and shared by every
 * caller. [TaigaApplication] owns the one instance; other packages reach it through
 * [Context.appContainer] and never construct these classes themselves.
 *
 * Every collaborator is built on [scope] by [start], in the order below: opening the database,
 * reading preferences and opening the Keystore are the app's start-up work, and nothing that runs on
 * the main thread pays for them. A caller that needs one before the build is done suspends in
 * [awaitReady] — the first screen does — instead of blocking the thread it is on.
 */
class AppContainer(private val application: Application) {
  /** Long-lived work of the wiring itself: what the stores publish and what start-up builds. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Completed when [start] has built every collaborator below. */
  private val ready = CompletableDeferred<Unit>()

  val recordingStore: RecordingStore by lazy {
    val base = application.getExternalFilesDir(null) ?: application.filesDir
    RecordingStore(File(base, "recordings"))
  }

  /** Everything the app learned from the server, in one database. */
  val database: TaigaDatabase by lazy { TaigaDatabase.open(application) }

  /** The sync state of every recording, for the account that is active. */
  val uploadStore: UploadStore by lazy {
    UploadStore(database.uploadStates(), remoteRepository.accounts, scope)
  }

  /**
   * The one HTTP client of the app. [HttpClients] derives the per-server clients from it with
   * `newBuilder()`, so every call shares its connection pool and dispatcher.
   */
  private val httpClient: OkHttpClient by lazy { OkHttpClient() }

  private val httpClients: HttpClients by lazy { HttpClients(httpClient) }

  /** The remote stack; its constructor reads the saved session and opens the token. */
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
    PendingDeleteStore(database.pendingDeletes(), remoteRepository.accounts, scope)
  }

  val resultCache: ResultCache by lazy { ResultCache(database.analysisResults()) }

  /**
   * The one read of the library: every fact the screens show about a recording, in one query joined
   * with the recordings on this phone. Every store that writes a row publishes through it.
   */
  val recordingRows: RecordingRows by lazy { RecordingRows(database.library()) }

  val remoteHistoryCache: RemoteHistoryCache by lazy {
    RemoteHistoryCache(database.remoteRecordings())
  }

  val analysisSettingsStore: AnalysisSettingsStore by lazy { AnalysisSettingsStore(application) }

  /** The preventing-shutdown steps the user confirmed and the flow they picked, for every screen. */
  val guidanceStore: GuidanceStore by lazy { GuidanceStore(application) }

  /** The recorder's stored choices: last tracker, its streams, its window and the live marker. */
  val recordingPrefs: RecordingPrefs by lazy { RecordingPrefs.get(application) }

  /** The automatic upload policy the screens show and the stop confirmation reads. */
  val uploadSettings: UploadSettings by lazy { UploadSettings.get(application) }

  /** The personal data the user entered, kept on the phone and read when SleepECG is requested. */
  val personalDataStore: PersonalDataStore by lazy { PersonalDataStore(application) }

  /** The theme, the text size and the animations: one store, read by the activity and the screen. */
  val displaySettings: DisplaySettings by lazy { DisplaySettings(application) }

  val recordingLibrary: RecordingLibrary by lazy { RecordingLibrary(application) }

  val syncService: SyncService by lazy { SyncService(AppSyncEnvironment(application), scope) }

  /** The one way an analysis is asked for, on every path that requests one. */
  internal val analysisRequests: AnalysisRequester by lazy {
    AnalysisRequester(AppAnalysisRequestEnvironment(application))
  }

  val sensorHub: SensorHub by lazy { SensorHub() }

  val liveSync: LiveSync by lazy {
    LiveSync(
        gate = remoteRepository.streamAccount,
        events = remoteRepository.events(),
        target = recordingLibrary,
    )
  }

  /**
   * Builds every collaborator on [scope], once, in dependency order: each one reads only what is
   * built before it. Called by the application before anything can ask for one.
   */
  fun start() {
    scope.launch {
      try {
        remoteRepository
        database
        uploadStore
        pendingDeleteStore
        resultCache
        recordingRows
        remoteHistoryCache
        analysisSettingsStore
        guidanceStore
        recordingPrefs
        uploadSettings
        personalDataStore
        displaySettings
        recordingStore
        analysisRequests
        syncService
        recordingLibrary
        liveSync
      } finally {
        // A build that failed still lets the callers through: the collaborator throws when it is
        // read, instead of leaving the first screen waiting for ever.
        ready.complete(Unit)
      }
    }
  }

  /** Suspends until [start] has built every collaborator; a caller waits here, not on disk. */
  suspend fun awaitReady() = ready.await()
}

/** The process-wide container, reached from any Context through the application object. */
val Context.appContainer: AppContainer
  get() = (applicationContext as TaigaApplication).container
