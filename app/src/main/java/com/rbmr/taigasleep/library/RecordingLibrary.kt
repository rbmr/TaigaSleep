package com.rbmr.taigasleep.library

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.sync.LiveSyncTarget
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
  private val sync = context.appContainer.syncService
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
            sync
                .observeRows(account, local.map { it.id })
                .map { entries -> buildRecordings(local, entries) }
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  override fun recording(key: String): RecordingView? = recordings.value.firstOrNull { it.key == key }

  /** The full cached payload for one analysis, if the active account's copy is on this phone. */
  override suspend fun cachedResult(key: String, analysisId: String?): AnalysisDto? =
      sync.cachedResult(key, analysisId)

  /** Whether another account already has a server copy of this recording. */
  override suspend fun uploadedElsewhere(localId: String): Boolean =
      sync.uploadedElsewhere(localId)

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
                  hidden = { sync.hiddenRecordings() },
                  save = { account, list -> sync.saveList(account, list) },
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
        val config = configFor(recording)
        sync.installedAlgorithms().map { installed -> installed.filter { compatible(it, config) } }
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
    sync.selectAnalysis(recording.key, analysisId)
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
        val shown = recordings.value.firstOrNull { it.key == key }?.analysisId
        sync.refresh(key, listOf(analysisId, shown)).map { it.shown }
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
      val entries = sync.loadRows(account(), local.map { it.id })
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
      // A recording the user deleted is only waiting for the server to say so.
      if (sync.isGone(recordingId)) return@withContext
      refreshRecording(keyFor(recordingId), recordingId, null)
    }
  }

  /**
   * The server says the recording no longer exists. Its row leaves History, without a read that
   * would only answer 404, and the saved list drops it so a cold start shows it no more. A local
   * copy stays, as the local recording it is.
   */
  override suspend fun recordingDeleted(recordingId: String) {
    withContext(Dispatchers.IO) { sync.forget(recordingId) }
  }

  /** The key of a recording: the phone's own id when it has the recording, else the remote key. */
  private fun keyFor(recordingId: String): String =
      if (context.appContainer.recordingStore.dir(recordingId).isDirectory) recordingId
      else remoteKey(recordingId)

  companion object {
    private val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)
  }
}
