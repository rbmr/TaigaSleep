package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.insights.AnalysisSettingsStore
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingStore
import kotlinx.coroutines.flow.StateFlow

/**
 * What [SyncService] needs from the rest of the app: the per-recording state, the session and the
 * settings, the phone's recordings, the queues and the cleanup steps.
 *
 * The service is written against this interface so its sequences can be tested without Android.
 * [AppSyncEnvironment] is the real one, and every member of it forwards to a piece that already
 * existed; it holds no rule of its own.
 */
internal interface SyncEnvironment {
  /** The durable sync state of every recording, and the writes the service makes to it. */
  val records: StateFlow<Map<String, UploadRecord>>

  fun record(id: String): UploadRecord?

  fun markLocal(id: String)

  fun markProcessing(id: String, analysisId: String?)

  fun markManualAnalysis(id: String, analysisId: String)

  /** The automatic upload policy as it is right now. */
  val autoUpload: AutoUploadSettings

  val hasSession: Boolean

  fun api(): TaigaClient?

  fun logout()

  /** Whether this id is a recording with a directory on this phone. */
  fun hasRecording(id: String): Boolean

  /** The stream configuration behind a night; a night that is only on the server is HR only. */
  fun config(id: String): RecordingConfig

  fun enqueueAuto(id: String)

  fun enqueueManual(id: String)

  fun cancelUploads(id: String)

  fun enqueueDelete(remoteId: String)

  fun resumePendingDeletes()

  suspend fun reconcileUploads()

  fun rescheduleAll()

  fun cancelLegacyPolls()

  fun deleteNight(localId: String?, remoteId: String?)

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
      id: String,
      recordingId: String,
      preferred: List<String?>,
  ): RecordingSnapshot
}

/** The app's [SyncEnvironment]: each member is the call the screens and the workers used to make. */
internal class AppSyncEnvironment(context: Context) : SyncEnvironment {
  private val app = context.applicationContext
  private val state = UploadStore.of(app)
  private val settings = UploadSettings.get(app)
  private val remote = RemoteRepository.of(app)

  override val records: StateFlow<Map<String, UploadRecord>> = state.records

  override fun record(id: String): UploadRecord? = state.record(id)

  override fun markLocal(id: String) = state.markLocal(id)

  override fun markProcessing(id: String, analysisId: String?) = state.markProcessing(id, analysisId)

  override fun markManualAnalysis(id: String, analysisId: String) =
      state.markManualAnalysis(id, analysisId)

  override val autoUpload: AutoUploadSettings
    get() = settings.autoUpload.value

  override val hasSession: Boolean
    get() = remote.hasSession

  override fun api(): TaigaClient? = remote.api()

  override fun logout() = remote.logout()

  override fun hasRecording(id: String): Boolean = RecordingStore.of(app).dir(id).isDirectory

  override fun config(id: String): RecordingConfig = localRecordingConfig(app, id)

  override fun enqueueAuto(id: String) = UploadScheduler.enqueueAuto(app, id)

  override fun enqueueManual(id: String) = UploadScheduler.enqueueManual(app, id)

  override fun cancelUploads(id: String) = UploadScheduler.cancelRecordingWork(app, id)

  override fun enqueueDelete(remoteId: String) = UploadScheduler.enqueueDelete(app, remoteId)

  override fun resumePendingDeletes() = UploadScheduler.resumePendingDeletes(app)

  override suspend fun reconcileUploads() = UploadScheduler.reconcile(app)

  override fun rescheduleAll() = UploadScheduler.rescheduleAll(app)

  override fun cancelLegacyPolls() = UploadScheduler.cancelLegacyPolls(app)

  override fun deleteNight(localId: String?, remoteId: String?) =
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
          AnalysisSettingsStore.of(app).settings.value,
      )

  override suspend fun installedAlgorithms(api: TaigaClient): Result<List<AlgorithmDto>> =
      AnalysisService.installedAlgorithms(api)

  override suspend fun retry(id: String, analysisId: String?): Result<Unit> =
      UploadCoordinator.retryAnalysis(app, id, analysisId)

  override suspend fun refresh(
      api: TaigaClient,
      id: String,
      recordingId: String,
      preferred: List<String?>,
  ): RecordingSnapshot =
      AnalysisSync(api, state, ResultCache.of(app)).refresh(id, recordingId, preferred)
}
