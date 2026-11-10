package com.rbmr.taigasleep.library

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.sync.LiveSyncTarget
import com.rbmr.taigasleep.sync.RecordingNotOnServerException
import com.rbmr.taigasleep.sync.RecordingSnapshot
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.compatible
import com.rbmr.taigasleep.sync.remoteKey
import com.rbmr.taigasleep.sync.withoutRecordings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place that turns local recordings, the app's durable recording state and the cached server
 * summaries into [RecordingView] snapshots. The UI reads this and never merges the stores itself.
 *
 * The raw bytes and the analyses stay independent: [RecordingView.backup] says where the bytes are and
 * [RecordingView.analyses] lists every requested result. [RecordingView.status] is only a derived display summary.
 */
class RecordingLibrary internal constructor(context: Context) : LiveSyncTarget, RecordingSource {
  private val context = context.applicationContext
  private val remote = context.appContainer.remoteRepository
  private val history = context.appContainer.remoteHistoryCache
  private val results = context.appContainer.resultCache
  private val sync = context.appContainer.syncService
  private val deletes = context.appContainer.pendingDeleteStore
  private val uploads = context.appContainer.uploadStore
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val remoteSummaries = MutableStateFlow<RecordingSummaryListDto?>(null)
  private val overrides = MutableStateFlow<Map<String, RemoteOverride>>(emptyMap())
  /** The run each recording shows, remembered by the cached History. */
  private val selectedAnalyses = MutableStateFlow<Map<String, String>>(emptyMap())
  private var loadedAccount: String? = null
  private val historyLock = Any()

  /**
   * Recordings whose server copy must not be shown: a delete is queued for them, or it completed
   * and the server no longer has them. The queue covers the first, the cache the second, and both
   * are needed because the queue entry is gone exactly when the delete has finished. Both are the
   * active account's, so another account's rows are untouched.
   */
  private val hiddenRemotely: StateFlow<Set<String>> =
      combine(deletes.pending, history.removed) { pending, removed -> pending.keys + removed }
          .stateIn(scope, SharingStarted.Eagerly, deletes.pending.value.keys)

  /** The one connection state, shared with the screens so they do not touch the remote store. */
  override val remoteStatus = remote.status

  /** The active account, which scopes everything the rows below are built from. */
  private fun account(): String = remote.accountKey.orEmpty()

  override val recordings: StateFlow<List<RecordingView>> =
      combine(
              combine(
                  RecordingRepository.sessions,
                  sync.records,
                  remoteSummaries,
                  overrides,
                  hiddenRemotely,
              ) { local, records, summary, fresh, hidden ->
                HistoryInputs(local, records, summary, fresh, hidden)
              },
              selectedAnalyses,
          ) { inputs, selected ->
            buildRecordings(
                recordings = inputs.local,
                records = inputs.records,
                remote = inputs.remote,
                overrides = inputs.overrides,
                hiddenRemoteIds = inputs.hidden,
                selectedAnalysisIds = selected,
                results = results,
                account = account(),
            )
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    // A delete that completed dropped the recording from the cached list; the copy in memory goes too.
    scope.launch {
      history.removed.collect { removed ->
        if (removed.isNotEmpty()) {
          remoteSummaries.update { it?.withoutRecordings(removed) }
        }
      }
    }
    reload()
  }

  /** Re-read the cached server list, for example after the account or the app resumed. */
  override fun reload() {
    history.refreshAccount()
    val account = remote.accountKey
    if (account != loadedAccount) {
      // A fresh recording list belongs to one account; never show it after switching accounts.
      overrides.value = emptyMap()
      selectedAnalyses.value = emptyMap()
    }
    if (account == null) {
      loadedAccount = null
      remoteSummaries.value = null
      return
    }
    // What an older version saved under the address and the email belongs to this account now.
    remote.legacyAccountKey?.let { history.adopt(it, account) }
    if (account == loadedAccount) return
    loadedAccount = account
    remoteSummaries.value = history.load(account)
    selectedAnalyses.value = history.selected(account)
  }

  override fun recording(key: String): RecordingView? = recordings.value.firstOrNull { it.key == key }

  /** The full cached payload for one analysis, if the active account's copy is on this phone. */
  override fun cachedResult(key: String, analysisId: String?): AnalysisDto? =
      results.load(account(), key, analysisId)

  /** Whether another account already has a server copy of this recording. */
  override suspend fun uploadedElsewhere(localId: String): Boolean =
      uploads.otherAccount(localId) != null

  /** Fetch the server's recording list and cache it. Does not touch local recordings. */
  suspend fun refreshList(): Result<Unit> =
      withContext(Dispatchers.IO) {
        val session =
            remote.session()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          fetchRemoteList(
              session = session,
              activeAccount = { remote.accountKey },
              hidden = ::hiddenNow,
              save = { account, list ->
                synchronized(historyLock) {
                  history.save(account, list)
                  remoteSummaries.value = list
                }
              },
          )
          Result.success(Unit)
        } catch (e: TaigaUnauthorizedException) {
          if (remote.accountKey == session.account) remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /** The stream configuration behind a local recording; a remote-only recording is treated as HR-only. */
  fun configFor(recording: RecordingView): RecordingConfig {
    val local = recording.local ?: return HR_ONLY
    return RecordingMeta.read(local.dir)?.config ?: HR_ONLY
  }

  /** Installed algorithms the server reports that this recording can actually feed. */
  override suspend fun compatibleAlgorithms(recording: RecordingView): Result<List<AlgorithmDto>> =
      withContext(Dispatchers.IO) {
        val session =
            remote.session()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val config = configFor(recording)
          Result.success(session.api.getAlgorithms().items.filter { compatible(it, config) })
        } catch (e: TaigaUnauthorizedException) {
          if (remote.accountKey == session.account) remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /**
   * Request one explicit analysis and remember that it was manual. Nothing waits for it: the event
   * stream reports it while the app is open, and the next open reads it otherwise.
   */
  override suspend fun requestManual(recording: RecordingView, algorithmId: String): Result<Unit> =
      withContext(Dispatchers.IO) {
        val server = recording.remoteId
            ?: return@withContext Result.failure(IllegalStateException("this night is not uploaded"))
        val account = remote.accountKey
            ?: return@withContext Result.failure(IllegalStateException("login required"))
        val started =
            sync.requestAnalysis(recording.key, algorithmId).getOrElse {
              return@withContext Result.failure(it)
            }
        if (remote.accountKey != account) return@withContext Result.success(Unit)
        refreshRecording(recording.key, server, started.first().analysisId)
        Result.success(Unit)
      }

  /** Remember which finished run the user wants to see. */
  override fun selectAnalysis(recording: RecordingView, analysisId: String) {
    rememberSelected(recording.key, analysisId)
  }

  /**
   * Refresh one recording and its analyses, once. The fetched analysis list replaces the
   * cached run summaries for this process, so several analyses are visible immediately, and the
   * recording's stored state, the result cache and the cached History list follow what the server says
   * (see [AnalysisSync]). The selected analysis is read when it is still listed.
   */
  override suspend fun refreshRecording(
      key: String,
      remoteId: String?,
      analysisId: String?,
  ): Result<AnalysisDto?> =
      withContext(Dispatchers.IO) {
        val server = remoteId ?: return@withContext Result.success(null)
        // The account is taken with the read: a login in between means this night is not the new
        // account's, so nothing of it is stored for it.
        val account = remote.accountKey
            ?: return@withContext Result.failure(IllegalStateException("login required"))
        val snapshot =
            sync.refresh(
                    id = key,
                    preferred =
                        listOf(
                            analysisId,
                            recordings.value.firstOrNull { it.key == key }?.analysisId,
                            selectedAnalyses.value[key],
                        ),
                )
                .getOrElse { e ->
                  if (e is RecordingNotOnServerException) {
                    if (remote.accountKey == account) forget(key, server)
                  }
                  return@withContext Result.failure(e)
                }
        if (remote.accountKey != account) return@withContext Result.success(snapshot.shown)
        val runs =
            snapshot.analyses.map { run ->
              run.toRun(downloaded = results.has(account, key, run.id))
            }
        overrides.update { it + (key to RemoteOverride(snapshot.recording, runs)) }
        rememberSummary(snapshot)
        // The run the server says this recording shows is the remembered one until the user picks
        // another. A recording with no run at all forgets it, as the record used to.
        rememberSelected(key, snapshot.shown?.id)
        Result.success(snapshot.shown)
      }

  /**
   * One refresh of everything that may be out of date, for when the event stream (re)connects and
   * the app opens: the History list, then every recording whose analysis is not settled (see
   * [pendingRecordings]), one at a time. A recording that finished, failed or vanished while the app
   * was closed is settled here, and a finished result that is not on this phone yet is downloaded.
   */
  override suspend fun reconcile() {
    withContext(Dispatchers.IO) {
      reload()
      if (refreshList().isFailure) return@withContext
      val hidden = hiddenNow()
      val records = sync.records.value
      // The recordings as the screens see them now, with the list that was just fetched. They
      // come from disk: the screens may not have loaded them yet when the app has just opened.
      val current =
          buildRecordings(
              recordings = context.appContainer.recordingStore.list(),
              records = records,
              remote = remoteSummaries.value,
              overrides = overrides.value,
              hiddenRemoteIds = hidden,
              selectedAnalysisIds = selectedAnalyses.value,
              results = results,
              account = account(),
          )
      for (pending in pendingRecordings(current, records)) {
        if (pending.recordingId in hidden) continue
        refreshRecording(pending.key, pending.recordingId, pending.analysisId)
      }
    }
  }

  /** One recording changed on the server: read it and its analyses. */
  override suspend fun refreshRecording(recordingId: String) {
    withContext(Dispatchers.IO) {
      // A recording the user deleted is only waiting for the server to say so.
      if (recordingId in hiddenNow()) return@withContext
      refreshRecording(keyFor(recordingId), recordingId, null)
    }
  }

  /**
   * The server says the recording no longer exists. Its row leaves History, without a read that
   * would only answer 404, and the saved list drops it so a cold start shows it no more. A local
   * copy stays, as the local recording it is.
   */
  override suspend fun recordingDeleted(recordingId: String) {
    withContext(Dispatchers.IO) {
      overrides.update { it - keyFor(recordingId) - remoteKey(recordingId) }
      dropRemote(recordingId)
    }
  }

  /** The key of a recording: the phone's own id when it has the recording, else the remote key. */
  private fun keyFor(recordingId: String): String =
      if (context.appContainer.recordingStore.dir(recordingId).isDirectory) recordingId
      else remoteKey(recordingId)

  /**
   * Keeps the cached History list in line with a recording that was just read, so a cold start
   * shows it too. Only what History shows is written to disk, which keeps an upload (a change of
   * the recording per chunk) from rewriting the cache each time.
   */
  private fun rememberSummary(snapshot: RecordingSnapshot) {
    if (snapshot.recording.id in hiddenNow()) return
    val fresh = RecordingSummaryDto(snapshot.recording, snapshot.analyses)
    var changed = false
    remoteSummaries.update { list ->
      if (list == null) return@update null
      val at = list.items.indexOfFirst { it.recording.id == fresh.recording.id }
      val old = list.items.getOrNull(at)
      changed =
          old == null ||
              old.analyses != fresh.analyses ||
              old.recording.complete != fresh.recording.complete
      RecordingSummaryListDto(
          if (at >= 0) list.items.toMutableList().also { it[at] = fresh } else list.items + fresh
      )
    }
    if (changed) saveSummaries()
  }

  /**
   * The run a recording shows is remembered with the cached History, not with the recording's sync
   * state: which run to show is a property of the row, and the run itself belongs to the server.
   */
  private fun rememberSelected(key: String, analysisId: String?) {
    if (selectedAnalyses.value[key] == analysisId) return
    selectedAnalyses.update {
      if (analysisId == null) it - key else it + (key to analysisId)
    }
    val account = remote.accountKey
    if (account != null) history.select(account, key, analysisId)
  }

  /**
   * The server says the recording does not exist (any more). It leaves the list, in memory and in
   * the saved copy, so History cannot keep it with an error. Its sync state says it is only on this
   * phone when the phone still has the recording.
   */
  private fun forget(key: String, recordingId: String) {
    overrides.update { it - key }
    dropRemote(recordingId)
  }

  /**
   * One recording is gone from the server: out of the list shown now and out of the saved one. A
   * recording with no copy on the phone stays out of lists fetched later, so an older fetch cannot
   * add it back; one that is still on the phone can be uploaded again, so it is only dropped now.
   */
  private fun dropRemote(recordingId: String) {
    remoteSummaries.update { it?.withoutRecordings(setOf(recordingId)) }
    val onPhone = context.appContainer.recordingStore.dir(recordingId).isDirectory
    remote.accountKey?.let { history.remove(it, recordingId, keepOut = !onPhone) }
  }

  /**
   * The recordings that are being deleted, or are already gone from the server. The queue is the one
   * the database publishes for the active account, the same one History reads.
   */
  private fun hiddenNow(): Set<String> = deletes.pending.value.keys + history.removed.value

  private fun saveSummaries() {
    synchronized(historyLock) {
      val list = remoteSummaries.value ?: return
      val account = remote.accountKey ?: return
      if (account != loadedAccount) return
      history.save(account, list.withoutRecordings(hiddenNow()))
    }
  }

  companion object {
    private val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)
  }
}

/** The five stores a History row is built from, taken together so the selection can join them. */
private data class HistoryInputs(
    val local: List<Recording>,
    val records: Map<String, UploadRecord>,
    val remote: RecordingSummaryListDto?,
    val overrides: Map<String, RemoteOverride>,
    val hidden: Set<String>,
)
