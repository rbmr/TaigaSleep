package com.rbmr.taigasleep.library

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.sync.LiveSyncTarget
import com.rbmr.taigasleep.sync.RecordingNotOnServerException
import com.rbmr.taigasleep.sync.RecordingSnapshot
import com.rbmr.taigasleep.sync.compatible
import com.rbmr.taigasleep.sync.remoteKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place that turns local recordings and the database's rows into [RecordingView] snapshots.
 * The UI reads this and never merges the stores itself.
 *
 * Everything the database knows about a recording is one query, parameterised by the active account
 * and joined with the recordings on this phone: the sync state, the server's list with its runs, the
 * run each recording shows, the payloads this phone holds and the recordings the server no longer
 * has. A write anywhere in the app reaches the screens without asking for one, and a recording the
 * phone does not have is a row of its own. The raw bytes and the analyses stay independent:
 * [RecordingView.backup] says where the bytes are and [RecordingView.analyses] lists every requested
 * result. [RecordingView.status] is only a derived display summary.
 */
class RecordingLibrary internal constructor(context: Context) : LiveSyncTarget, RecordingSource {
  private val context = context.applicationContext
  private val remote = context.appContainer.remoteRepository
  private val history = context.appContainer.remoteHistoryCache
  private val results = context.appContainer.resultCache
  private val rows = context.appContainer.recordingRows
  private val sync = context.appContainer.syncService
  private val deletes = context.appContainer.pendingDeleteStore
  private val uploads = context.appContainer.uploadStore
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The one connection state, shared with the screens so they do not touch the remote store. */
  override val remoteStatus = remote.status

  /** The active account, which scopes everything the query below reads. */
  private fun account(): String = remote.accountKey.orEmpty()

  /**
   * The recordings as the screens see them: the entries of the active account, joined with the
   * recordings on this phone. A change of account re-runs the query under the new account, so one
   * account's rows are never drawn for another.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  override val recordings: StateFlow<List<RecordingView>> =
      combine(RecordingRepository.sessions, remote.accounts) { local, account ->
            local to account.orEmpty()
          }
          .flatMapLatest { (local, account) ->
            rows
                .observe(account, local.map { it.id })
                .map { entries -> buildRecordings(local, entries) }
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  override fun recording(key: String): RecordingView? = recordings.value.firstOrNull { it.key == key }

  /** The full cached payload for one analysis, if the active account's copy is on this phone. */
  override suspend fun cachedResult(key: String, analysisId: String?): AnalysisDto? =
      results.load(account(), key, analysisId)

  /** Whether another account already has a server copy of this recording. */
  override suspend fun uploadedElsewhere(localId: String): Boolean =
      uploads.otherAccount(localId) != null

  /**
   * Reads the list, saves it for [session]'s account and returns what was saved, or null when the
   * session ended during the fetch.
   */
  private suspend fun fetchList(): Result<RecordingSummaryListDto?> =
      withContext(Dispatchers.IO) {
        val session =
            remote.session()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          Result.success(
              fetchRemoteList(
                  session = session,
                  activeAccount = { remote.accountKey },
                  hidden = { hiddenForFetch() },
                  save = { account, list -> history.save(account, list) },
              )
          )
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
            ?: return@withContext Result.failure(IllegalStateException("this recording is not uploaded"))
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
   * Refresh one recording and its analyses, once. The fetched analysis list replaces the runs the
   * database holds for this recording, so several analyses are visible immediately, and the
   * recording's stored state, the result cache and the cached History list follow what the server
   * says (see [AnalysisSync]). The selected analysis is read when it is still listed.
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
                            history.selected(account, key),
                        ),
                )
                .getOrElse { e ->
                  if (e is RecordingNotOnServerException) {
                    if (remote.accountKey == account) dropRemote(server)
                  }
                  return@withContext Result.failure(e)
                }
        if (remote.accountKey != account) return@withContext Result.success(snapshot.shown)
        rememberSummary(snapshot)
        // The run the server says this recording shows is the remembered one until the user picks
        // another. A recording with no run at all forgets it, as the record used to.
        rememberSelected(key, snapshot.shown?.id)
        Result.success(snapshot.shown)
      }

  /**
   * One refresh of everything that may be out of date, for when the realtime stream (re)connects and
   * the app opens: the History list, then every recording whose analysis is not settled (see
   * [pendingRecordings]), one at a time. A recording that finished, failed or vanished while the app
   * was closed is settled here, and a finished result that is not on this phone yet is downloaded.
   */
  override suspend fun reconcile() {
    withContext(Dispatchers.IO) {
      fetchList().getOrNull() ?: return@withContext
      // The recordings as the screens see them now, with the list that was just fetched. They
      // come from disk: the screens may not have loaded them yet when the app has just opened.
      val local = context.appContainer.recordingStore.list()
      val entries = rows.load(account(), local.map { it.id })
      val current = buildRecordings(local, entries)
      val records = sync.records.value
      val hiddenKeys = entries.filter { it.hidden }.map { it.key }.toSet()
      for (pending in pendingRecordings(current, records)) {
        if (pending.key in hiddenKeys) continue
        refreshRecording(pending.key, pending.recordingId, pending.analysisId)
      }
    }
  }

  /** One recording changed on the server: read it and its analyses. */
  override suspend fun refreshRecording(recordingId: String) {
    withContext(Dispatchers.IO) {
      val account = account()
      // A recording the user deleted is only waiting for the server to say so.
      if (history.isHidden(account, recordingId)) return@withContext
      if (recordingId in deletes.remoteIds(account)) return@withContext
      refreshRecording(keyFor(recordingId), recordingId, null)
    }
  }

  /**
   * The server says the recording no longer exists. Its row leaves History, without a read that
   * would only answer 404, and the saved list drops it so a cold start shows it no more. A local
   * copy stays, as the local recording it is.
   */
  override suspend fun recordingDeleted(recordingId: String) {
    withContext(Dispatchers.IO) { dropRemote(recordingId) }
  }

  /** The key of a recording: the phone's own id when it has the recording, else the remote key. */
  private fun keyFor(recordingId: String): String =
      if (context.appContainer.recordingStore.dir(recordingId).isDirectory) recordingId
      else remoteKey(recordingId)

  /**
   * The saved list follows a recording that was just read: its row keeps its place, one the list
   * does not have yet is added, and the runs become the ones the server lists now.
   */
  private suspend fun rememberSummary(snapshot: RecordingSnapshot) {
    val account = remote.accountKey ?: return
    history.saveSummary(account, RecordingSummaryDto(snapshot.recording, snapshot.analyses))
  }

  /**
   * The run a recording shows is remembered with the cached History, not with the recording's sync
   * state: which run to show is a property of the row, and the run itself belongs to the server.
   */
  private fun rememberSelected(key: String, analysisId: String?) {
    val account = remote.accountKey ?: return
    scope.launch { history.select(account, key, analysisId) }
  }

  /**
   * One recording is gone from the server: out of the list shown now and out of the saved one. A
   * recording with no copy on the phone stays out of lists fetched later, so an older fetch cannot
   * add it back; one that is still on the phone can be uploaded again, so it is only dropped now.
   */
  private suspend fun dropRemote(recordingId: String) {
    val onPhone = context.appContainer.recordingStore.dir(recordingId).isDirectory
    remote.accountKey?.let { history.remove(it, recordingId, keepOut = !onPhone) }
  }

  /**
   * The recordings the server copy of which is being deleted, or is already gone. The queue and the
   * markers are rows of the active account, so both are read for the account that is fetching.
   */
  private suspend fun hiddenForFetch(): Set<String> {
    val account = account()
    return deletes.remoteIds(account) + history.removed(account)
  }

  companion object {
    private val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)
  }
}
