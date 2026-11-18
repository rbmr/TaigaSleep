package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.notifications.ResultNotifications
import com.rbmr.taigasleep.recording.PhoneStorageWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

  /** The active account key, or null while logged out. */
  val account: String?

  /** The account and a client for it, captured together; null while logged out. */
  fun session(): RemoteSession?

  fun logout()

  /** Whether this id is a recording with a directory on this phone. */
  fun hasRecording(id: String): Boolean

  suspend fun enqueueManual(id: String)

  fun cancelUploads(id: String)

  fun enqueueDelete(remoteId: String)

  suspend fun resumePendingDeletes()

  /** Do the automatic upload and analysis work that is due ([only] limits it to one recording). */
  suspend fun catchUp(reason: CatchUpReason, only: String?)

  /** Keep the periodic catch-up that is the safety net of the events. */
  fun schedulePeriodicCatchUp()

  /** Run a catch-up whenever a switch, the server or the account changes. */
  fun watchTriggers(scope: CoroutineScope)

  suspend fun deleteRecording(localId: String?, remoteId: String?)

  suspend fun enforceRawRetention()

  suspend fun deleteRaw(id: String): RawActionResult

  suspend fun restoreRaw(id: String, onProgress: (Float) -> Unit): Result<Unit>

  /** Ask for explicit methods through the one requester; incompatible ids are skipped. */
  suspend fun startManual(
      session: RemoteSession,
      id: String,
      algorithm: String,
  ): List<StartedAnalysis>

  /** Ask for the preferred compatible method through the one requester. */
  suspend fun startPreferred(session: RemoteSession, id: String): StartedAnalysis

  suspend fun installedAlgorithms(api: TaigaClient): Result<List<AlgorithmDto>>

  suspend fun retry(id: String, analysisId: String?): Result<Unit>

  suspend fun refresh(
      api: TaigaClient,
      account: String,
      id: String,
      recordingId: String,
      preferred: List<String?>,
  ): RecordingSnapshot

  // The stored view the library reads through the service, and the writes the service makes to the
  // same rows. An environment without a database answers with nothing and writes nothing, so the
  // sequences above can be exercised without one.

  /** The library rows of [account], with [localIds] the recordings on this phone. */
  fun observeRows(account: String, localIds: List<String>): Flow<List<LibraryEntry>> =
      flowOf(emptyList())

  /** The same read, once, for a step that is not drawing a screen. */
  suspend fun loadRows(account: String, localIds: List<String>): List<LibraryEntry> = emptyList()

  /** The full payload of one run the phone holds, or null when it is not here. */
  suspend fun cachedResult(key: String, analysisId: String?, account: String? = null): AnalysisDto? =
      null

  /** The queued server deletes of the account that is active now. */
  val pendingDeletes: StateFlow<Map<String, PendingDelete>>
    get() = MutableStateFlow(emptyMap())

  /** Whether another account already has a server copy of [localId]. */
  suspend fun uploadedElsewhere(localId: String): Boolean = false

  /** The run of [key] the user picked, for [account]; it is a preference of the next read. */
  suspend fun selectedAnalysis(key: String, account: String? = null): String? = null

  /** Remember the run a recording shows. Null forgets it. */
  suspend fun selectAnalysis(key: String, analysisId: String?, account: String? = null) {}

  /** Replace the server's list for [account] with [list]. */
  suspend fun saveList(account: String, list: RecordingSummaryListDto) {}

  /** One recording as a read of it just found it: its row keeps its place, its runs are replaced. */
  suspend fun saveSummary(account: String, snapshot: RecordingSnapshot) {}

  /** The recordings a fetched list must leave out: queued for deletion, or already gone. */
  suspend fun hiddenRecordings(): Set<String> = emptySet()

  /** Whether the server copy of [recordingId] is being deleted, or is already gone. */
  suspend fun isGone(recordingId: String): Boolean = false

  /**
   * The server no longer has [recordingId]: it leaves the list, and stays out of later fetches while
   * no copy of it remains on this phone.
   */
  suspend fun forget(recordingId: String) {}
}

/** The app's [SyncEnvironment]: each member is the call the screens and the workers used to make. */
internal class AppSyncEnvironment(context: Context) : SyncEnvironment {
  private val app = context.applicationContext
  private val state = app.appContainer.uploadStore
  private val settings = UploadSettings.get(app)
  private val remote = app.appContainer.remoteRepository
  private val history = app.appContainer.remoteHistoryCache
  private val results = app.appContainer.resultCache
  private val rows = app.appContainer.recordingRows
  private val deletes = app.appContainer.pendingDeleteStore

  override val records: StateFlow<Map<String, UploadRecord>> = state.records

  override suspend fun record(id: String, account: String?): UploadRecord? = state.record(id, account)

  override suspend fun markLocal(id: String, account: String?) = state.markLocal(id, account)

  override suspend fun markProcessing(id: String, account: String?) =
      state.markProcessing(id, account)

  override suspend fun markAnalysisFailed(id: String, detail: String?, account: String?) {
    state.markFailed(id, UploadFailure.ANALYSIS, detail, account)
    ResultNotifications(app).analysisFailed()
  }

  override suspend fun markManualAnalysis(id: String, analysisId: String, account: String?) =
      state.markManualAnalysis(id, analysisId, account)

  override val account: String?
    get() = remote.accountKey

  override fun session(): RemoteSession? = remote.session()

  override fun logout() = remote.logout()

  override fun hasRecording(id: String): Boolean = app.appContainer.recordingStore.dir(id).isDirectory

  private val requests: AnalysisRequester get() = app.appContainer.analysisRequests

  override suspend fun enqueueManual(id: String) = UploadScheduler.enqueueManual(app, id)

  override fun cancelUploads(id: String) = UploadScheduler.cancelRecordingWork(app, id)

  override fun enqueueDelete(remoteId: String) = UploadScheduler.enqueueDelete(app, remoteId)

  override suspend fun resumePendingDeletes() = UploadScheduler.resumePendingDeletes(app)

  private val automation = Automation(AppAutomationEnvironment(app))

  override suspend fun catchUp(reason: CatchUpReason, only: String?) {
    automation.run(reason, only)
    // The pass is over: the same free-space answer the recording checks, without waiting for one.
    PhoneStorageWatch.check(app)
  }

  override fun schedulePeriodicCatchUp() = CatchUpWorker.schedule(app)

  override fun watchTriggers(scope: CoroutineScope) {
    automation.watch(
        scope = scope,
        uploadSettings = settings.autoUpload,
        analysisAuto = app.appContainer.analysisSettingsStore.settings.map { it.auto },
        accounts = remote.accounts,
        connected = remote.status.map { it is RemoteStatus.Connected },
        onTrigger = { catchUp(it, null) },
    )
  }

  override suspend fun deleteRecording(localId: String?, remoteId: String?) =
      RecordingDeleter.delete(app, localId, remoteId)

  override suspend fun enforceRawRetention() = RawRetention.enforce(app)

  override suspend fun deleteRaw(id: String): RawActionResult = RawRetention.deleteRaw(app, id)

  override suspend fun restoreRaw(id: String, onProgress: (Float) -> Unit): Result<Unit> =
      RawRetention.restore(app, id, recordingIdOf(id), onProgress)

  override suspend fun startManual(
      session: RemoteSession,
      id: String,
      algorithm: String,
  ): List<StartedAnalysis> = requests.startManual(session, id, listOf(algorithm))

  override suspend fun startPreferred(session: RemoteSession, id: String): StartedAnalysis =
      requests.startPreferred(session, id, app.appContainer.analysisSettingsStore.settings.value)

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
      AnalysisSync(api, state, app.appContainer.resultCache, account) { outcome ->
            when (outcome) {
              AnalysisOutcome.FINISHED -> ResultNotifications(app).analysisFinished()
              AnalysisOutcome.FAILED -> ResultNotifications(app).analysisFailed()
            }
          }
          .refresh(id, recordingId, preferred)

  override fun observeRows(account: String, localIds: List<String>): Flow<List<LibraryEntry>> =
      rows.observe(account, localIds)

  override suspend fun loadRows(account: String, localIds: List<String>): List<LibraryEntry> =
      rows.load(account, localIds)

  override suspend fun cachedResult(
      key: String,
      analysisId: String?,
      account: String?,
  ): AnalysisDto? = results.load(account ?: remote.accountKey.orEmpty(), key, analysisId)

  override val pendingDeletes: StateFlow<Map<String, PendingDelete>> = deletes.pending

  override suspend fun uploadedElsewhere(localId: String): Boolean = state.otherAccount(localId) != null

  override suspend fun selectedAnalysis(key: String, account: String?): String? =
      history.selected(account ?: remote.accountKey.orEmpty(), key)

  override suspend fun selectAnalysis(key: String, analysisId: String?, account: String?) {
    history.select(account ?: remote.accountKey.orEmpty(), key, analysisId)
  }

  override suspend fun saveList(account: String, list: RecordingSummaryListDto) =
      history.save(account, list)

  override suspend fun saveSummary(account: String, snapshot: RecordingSnapshot) {
    history.saveSummary(account, RecordingSummaryDto(snapshot.recording, snapshot.analyses))
  }

  override suspend fun hiddenRecordings(): Set<String> {
    val account = remote.accountKey.orEmpty()
    return deletes.remoteIds(account) + history.removed(account)
  }

  override suspend fun isGone(recordingId: String): Boolean {
    val account = remote.accountKey.orEmpty()
    return history.isHidden(account, recordingId) || recordingId in deletes.remoteIds(account)
  }

  override suspend fun forget(recordingId: String) {
    val account = remote.accountKey ?: return
    // A recording that is still on the phone can be uploaded again, so its row is only dropped now.
    history.remove(account, recordingId, keepOut = !hasRecording(recordingId))
  }
}
