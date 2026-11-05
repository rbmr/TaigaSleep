package com.rbmr.taigasleep.library

import android.content.Context
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.recordingSummaries
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.AnalysisService
import com.rbmr.taigasleep.sync.AnalysisSync
import com.rbmr.taigasleep.sync.LiveSyncTarget
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.RecordingSnapshot
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.compatible
import com.rbmr.taigasleep.sync.remoteKey
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
import kotlinx.coroutines.withContext

/**
 * The one place that turns local recordings, the app's durable night state and the cached server
 * summaries into [RecordingView] snapshots. The UI reads this and never merges the stores itself.
 *
 * The raw bytes and the analyses stay independent: [RecordingView.backup] says where the bytes are and
 * [RecordingView.analyses] lists every requested result. [RecordingView.status] is only a derived display summary.
 */
class RecordingLibrary private constructor(context: Context) : LiveSyncTarget {
  private val context = context.applicationContext
  private val remote = RemoteRepository.of(this.context)
  private val uploads = UploadStore.of(this.context)
  private val history = RemoteHistoryCache.of(this.context)
  private val results = ResultCache.of(this.context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val remoteSummaries = MutableStateFlow<RecordingSummaryListDto?>(null)
  private val overrides = MutableStateFlow<Map<String, RemoteOverride>>(emptyMap())
  private var loadedAccount: String? = null
  private val historyLock = Any()

  /** The one connection state, shared with the screens so they do not touch the remote store. */
  val remoteStatus = remote.status

  val recordings: StateFlow<List<RecordingView>> =
      combine(
              RecordingRepository.sessions,
              uploads.records,
              remoteSummaries,
              overrides,
          ) { local, records, summary, fresh ->
            buildRecordings(
                recordings = local,
                records = records,
                remote = summary,
                overrides = fresh,
            )
          }
          .stateIn(scope, SharingStarted.Eagerly, emptyList())

  init {
    reload()
  }

  /** Re-read the cached server list, for example after the account or the app resumed. */
  fun reload() {
    val account = remote.accountKey
    if (account != loadedAccount) {
      // A fresh recording list belongs to one account; never show it after switching accounts.
      overrides.value = emptyMap()
    }
    if (account.isBlank()) {
      loadedAccount = null
      remoteSummaries.value = null
      return
    }
    if (account == loadedAccount) return
    loadedAccount = account
    remoteSummaries.value = history.load(account)
  }

  fun recording(key: String): RecordingView? = recordings.value.firstOrNull { it.key == key }

  /** The full cached payload for one analysis, if it is on this phone. */
  fun cachedResult(key: String, analysisId: String?): AnalysisDto? =
      results.load(key, analysisId)

  /** Fetch the server's recording list and cache it. Does not touch local recordings. */
  suspend fun refreshList(): Result<Unit> =
      withContext(Dispatchers.IO) {
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val list = api.recordingSummaries()
          synchronized(historyLock) {
            history.save(remote.accountKey, list)
            remoteSummaries.value = list
          }
          Result.success(Unit)
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /** The stream configuration behind a local night; a remote-only night is treated as HR-only. */
  fun configFor(recording: RecordingView): RecordingConfig {
    val local = recording.local ?: return HR_ONLY
    return RecordingMeta.read(local.dir)?.config ?: HR_ONLY
  }

  /** Installed algorithms the server reports that this recording can actually feed. */
  suspend fun compatibleAlgorithms(recording: RecordingView): Result<List<AlgorithmDto>> =
      withContext(Dispatchers.IO) {
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val config = configFor(recording)
          Result.success(api.getAlgorithms().items.filter { compatible(it, config) })
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
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
  suspend fun requestManual(recording: RecordingView, algorithmId: String): Result<Unit> =
      withContext(Dispatchers.IO) {
        val server = recording.remoteId
            ?: return@withContext Result.failure(IllegalStateException("this night is not uploaded"))
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val started = AnalysisService.startManual(api, server, configFor(recording), listOf(algorithmId))
          if (started.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("no compatible algorithm is installed")
            )
          }
          for (run in started) {
            uploads.markManualAnalysis(recording.key, run.analysisId)
            uploads.markProcessing(recording.key, run.analysisId)
          }
          refreshRecording(recording.key, server, started.first().analysisId)
          Result.success(Unit)
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /** Remember which finished run the user wants to see. */
  fun selectAnalysis(recording: RecordingView, analysisId: String) {
    uploads.markSelectedAnalysis(recording.key, analysisId)
  }

  /**
   * Refresh one night's recording and its analyses, once. The fetched analysis list replaces the
   * cached run summaries for this process, so several analyses are visible immediately, and the
   * night's stored state, the result cache and the cached History list follow what the server says
   * (see [AnalysisSync]). The selected analysis is read when it is still listed.
   */
  suspend fun refreshRecording(
      key: String,
      remoteId: String?,
      analysisId: String?,
  ): Result<AnalysisDto?> =
      withContext(Dispatchers.IO) {
        val server = remoteId ?: return@withContext Result.success(null)
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val snapshot =
              AnalysisSync(api, uploads, results)
                  .refresh(
                      key = key,
                      recordingId = server,
                      preferred =
                          listOf(
                              analysisId,
                              recordings.value.firstOrNull { it.key == key }?.analysisId,
                              uploads.record(key)?.analysisId,
                          ),
                  )
          val runs = snapshot.analyses.map { run -> run.toRun(downloaded = results.has(key, run.id)) }
          overrides.update { it + (key to RemoteOverride(snapshot.recording, runs)) }
          rememberSummary(snapshot)
          Result.success(snapshot.shown)
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: TaigaHttpException) {
          if (e.status == 404) forget(key, server, e)
          Result.failure(e)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /**
   * One refresh of everything that may be out of date, for when the event stream (re)connects and
   * the app opens: the History list, then every night whose analysis is not settled (see
   * [pendingRecordings]), one at a time. A night that finished, failed or vanished while the app
   * was closed is settled here, and a finished result that is not on this phone yet is downloaded.
   */
  override suspend fun reconcile() {
    withContext(Dispatchers.IO) {
      reload()
      if (refreshList().isFailure) return@withContext
      val pendingDeletes = PendingDeleteStore.of(context).remoteIds()
      val records = uploads.records.value
      // The nights as the screens see them now, with the list that was just fetched. The sessions
      // come from disk: the screens may not have loaded them yet when the app has just opened.
      val current =
          buildRecordings(
              recordings = RecordingStore.of(context).list(),
              records = records,
              remote = remoteSummaries.value,
              overrides = overrides.value,
          )
      for (pending in pendingRecordings(current, records)) {
        if (pending.recordingId in pendingDeletes) continue
        refreshRecording(pending.key, pending.recordingId, pending.analysisId)
      }
    }
  }

  /** One recording changed on the server: read it and its analyses. */
  override suspend fun refreshRecording(recordingId: String) {
    withContext(Dispatchers.IO) {
      // A recording the user deleted is only waiting for the server to say so.
      if (recordingId in PendingDeleteStore.of(context).remoteIds()) return@withContext
      refreshRecording(keyFor(recordingId), recordingId, null)
    }
  }

  /** The night key of a recording: the phone's own id when it has the recording, else the remote key. */
  private fun keyFor(recordingId: String): String =
      if (RecordingStore.of(context).dir(recordingId).isDirectory) recordingId
      else remoteKey(recordingId)

  /**
   * Keeps the cached History list in line with a recording that was just read, so a cold start
   * shows it too. Only what History shows is written to disk, which keeps an upload (a change of
   * the recording per chunk) from rewriting the cache each time.
   */
  private fun rememberSummary(snapshot: RecordingSnapshot) {
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
   * The server says the recording does not exist (any more). Its fresh state is dropped, and a night
   * that was waiting for its analysis is marked failed instead of processing for ever.
   */
  private fun forget(key: String, recordingId: String, cause: TaigaHttpException) {
    overrides.update { it - key }
    remoteSummaries.update { list ->
      list?.let { RecordingSummaryListDto(it.items.filterNot { s -> s.recording.id == recordingId }) }
    }
    saveSummaries()
    if (uploads.record(key)?.phase == UploadPhase.PROCESSING) {
      uploads.markFailed(key, UploadFailure.ANALYSIS, cause.message)
    }
  }

  private fun saveSummaries() {
    synchronized(historyLock) {
      val list = remoteSummaries.value ?: return
      val account = remote.accountKey
      if (account.isBlank() || account != loadedAccount) return
      history.save(account, list)
    }
  }

  companion object {
    private val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)

    @Volatile private var instance: RecordingLibrary? = null

    fun of(context: Context): RecordingLibrary =
        instance
            ?: synchronized(this) {
              instance ?: RecordingLibrary(context).also { instance = it }
            }
  }
}
