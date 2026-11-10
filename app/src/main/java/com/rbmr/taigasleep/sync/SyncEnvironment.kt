package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.recording.store.RecordingConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * What [SyncService] needs from the rest of the app: the per-recording state, the session and the
 * settings, the phone's recordings, the queues and the cleanup steps.
 *
 * The service is written against this interface so its sequences can be tested without Android.
 * [AppSyncEnvironment] is the real one, and every member of it forwards to a piece that already
 * existed; it holds no rule of its own. The members that read or write the durable state are
 * suspend, because that state is a database now.
 */
internal interface SyncEnvironment {
  /** The durable sync state of every recording, and the writes the service makes to it. */
  val records: StateFlow<Map<String, UploadRecord>>

  suspend fun record(id: String, account: String? = null): UploadRecord?

  suspend fun markLocal(id: String, account: String? = null)

  suspend fun markProcessing(id: String, account: String? = null)

  suspend fun markAnalysisFailed(id: String, detail: String?, account: String? = null)

  suspend fun markManualAnalysis(id: String, analysisId: String, account: String? = null)

  /** The automatic upload policy as it is right now. */
  val autoUpload: AutoUploadSettings

  val hasSession: Boolean

  /** The active account key, or null while logged out. */
  val account: String?

  /** The account and a client for it, captured together; null while logged out. */
  fun session(): RemoteSession?

  fun logout()

  /** Whether this id is a recording with a directory on this phone. */
  fun hasRecording(id: String): Boolean

  /** The stream configuration behind a recording; a recording that is only on the server is HR only. */
  fun config(id: String): RecordingConfig

  suspend fun enqueueAuto(id: String)

  suspend fun enqueueManual(id: String)

  fun cancelUploads(id: String)

  fun enqueueDelete(remoteId: String)

  suspend fun resumePendingDeletes()

  suspend fun reconcileUploads()

  suspend fun rescheduleAll()

  suspend fun deleteRecording(localId: String?, remoteId: String?)

  suspend fun enforceRawRetention()

  suspend fun deleteRaw(id: String): RawActionResult

  suspend fun restoreRaw(id: String, onProgress: (Float) -> Unit): Result<Unit>

  suspend fun startManual(
      api: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      algorithm: String,
  ): List<StartedAnalysis>

  suspend fun startPreferred(
      api: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
  ): StartedAnalysis

  suspend fun installedAlgorithms(api: TaigaClient): Result<List<AlgorithmDto>>

  suspend fun retry(id: String, analysisId: String?): Result<Unit>

  suspend fun refresh(
      api: TaigaClient,
      account: String,
      id: String,
      recordingId: String,
      preferred: List<String?>,
  ): RecordingSnapshot
}

/** The app's [SyncEnvironment]: each member is the call the screens and the workers used to make. */
internal class AppSyncEnvironment(context: Context) : SyncEnvironment {
  private val app = context.applicationContext
  private val state = app.appContainer.uploadStore
  private val settings = UploadSettings.get(app)
  private val remote = app.appContainer.remoteRepository

  override val records: StateFlow<Map<String, UploadRecord>> = state.records

  override suspend fun record(id: String, account: String?): UploadRecord? = state.record(id, account)

  override suspend fun markLocal(id: String, account: String?) = state.markLocal(id, account)

  override suspend fun markProcessing(id: String, account: String?) =
      state.markProcessing(id, account)

  override suspend fun markAnalysisFailed(id: String, detail: String?, account: String?) =
      state.markFailed(id, UploadFailure.ANALYSIS, detail, account)

  override suspend fun markManualAnalysis(id: String, analysisId: String, account: String?) =
      state.markManualAnalysis(id, analysisId, account)

  override val autoUpload: AutoUploadSettings
    get() = settings.autoUpload.value

  override val hasSession: Boolean
    get() = remote.hasSession

  override val account: String?
    get() = remote.accountKey

  override fun session(): RemoteSession? = remote.session()

  override fun logout() = remote.logout()

  override fun hasRecording(id: String): Boolean = app.appContainer.recordingStore.dir(id).isDirectory

  override fun config(id: String): RecordingConfig = localRecordingConfig(app, id)

  override suspend fun enqueueAuto(id: String) = UploadScheduler.enqueueAuto(app, id)

  override suspend fun enqueueManual(id: String) = UploadScheduler.enqueueManual(app, id)

  override fun cancelUploads(id: String) = UploadScheduler.cancelRecordingWork(app, id)

  override fun enqueueDelete(remoteId: String) = UploadScheduler.enqueueDelete(app, remoteId)

  override suspend fun resumePendingDeletes() = UploadScheduler.resumePendingDeletes(app)

  override suspend fun reconcileUploads() = UploadScheduler.reconcile(app)

  override suspend fun rescheduleAll() = UploadScheduler.rescheduleAll(app)

  override suspend fun deleteRecording(localId: String?, remoteId: String?) =
      RecordingDeleter.delete(app, localId, remoteId)

  override suspend fun enforceRawRetention() = RawRetention.enforce(app)

  override suspend fun deleteRaw(id: String): RawActionResult = RawRetention.deleteRaw(app, id)

  override suspend fun restoreRaw(id: String, onProgress: (Float) -> Unit): Result<Unit> =
      RawRetention.restore(app, id, recordingIdOf(id), onProgress)

  override suspend fun startManual(
      api: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      algorithm: String,
  ): List<StartedAnalysis> =
      AnalysisService.startManual(api, recordingId, config, listOf(algorithm))

  override suspend fun startPreferred(
      api: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
  ): StartedAnalysis =
      AnalysisService.startPreferred(
          api,
          recordingId,
          config,
          app.appContainer.analysisSettingsStore.settings.value,
      )

  override suspend fun installedAlgorithms(api: TaigaClient): Result<List<AlgorithmDto>> =
      AnalysisService.installedAlgorithms(api)

  override suspend fun retry(id: String, analysisId: String?): Result<Unit> =
      UploadCoordinator.retryAnalysis(app, id, analysisId)

  override suspend fun refresh(
      api: TaigaClient,
      account: String,
      id: String,
      recordingId: String,
      preferred: List<String?>,
  ): RecordingSnapshot =
      AnalysisSync(api, state, app.appContainer.resultCache, account)
          .refresh(id, recordingId, preferred)
}
