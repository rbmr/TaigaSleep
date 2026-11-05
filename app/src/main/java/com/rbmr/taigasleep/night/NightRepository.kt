package com.rbmr.taigasleep.night

import android.content.Context
import com.rbmr.taigasleep.analysis.AnalysisService
import com.rbmr.taigasleep.analysis.compatible
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.RecordingSummaryDto
import com.rbmr.taigasleep.remote.RecordingSummaryListDto
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.TaigaHttpException
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.remote.recordingSummaries
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.storage.SessionStore
import com.rbmr.taigasleep.upload.AnalysisSync
import com.rbmr.taigasleep.upload.LiveSyncTarget
import com.rbmr.taigasleep.upload.PendingDeleteStore
import com.rbmr.taigasleep.upload.RecordingSnapshot
import com.rbmr.taigasleep.upload.RemoteHistoryCache
import com.rbmr.taigasleep.upload.ResultCache
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadPhase
import com.rbmr.taigasleep.upload.UploadRecord
import com.rbmr.taigasleep.upload.UploadStore
import com.rbmr.taigasleep.upload.pendingRecordings
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
 * summaries into [Night] snapshots. The UI reads this and never merges the stores itself.
 *
 * The raw bytes and the analyses stay independent: [Night.backup] says where the bytes are and
 * [Night.analyses] lists every requested result. [Night.status] is only a derived display summary.
 */
class NightRepository private constructor(context: Context) : LiveSyncTarget {
  private val context = context.applicationContext
  private val remote = RemoteRepository.of(this.context)
  private val uploads = UploadStore.of(this.context)
  private val history = RemoteHistoryCache.of(this.context)
  private val results = ResultCache.of(this.context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val remoteSummaries = MutableStateFlow<RecordingSummaryListDto?>(null)
  private val overrides = MutableStateFlow<Map<String, NightRemoteOverride>>(emptyMap())
  private var loadedAccount: String? = null
  private val historyLock = Any()

  /** The one connection state, shared with the screens so they do not touch the remote store. */
  val remoteStatus = remote.status

  val nights: StateFlow<List<Night>> =
      combine(
              RecordingRepository.sessions,
              uploads.records,
              remoteSummaries,
              overrides,
          ) { local, records, summary, fresh ->
            buildNights(
                localSessions = local,
                records = records.mapValues { it.value.toNightRecord() },
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

  fun night(key: String): Night? = nights.value.firstOrNull { it.key == key }

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
  fun configFor(night: Night): SessionConfig {
    val local = night.local ?: return HR_ONLY
    return SessionMeta.read(local.dir)?.config ?: HR_ONLY
  }

  /** Installed algorithms the server reports that this recording can actually feed. */
  suspend fun compatibleAlgorithms(night: Night): Result<List<AlgorithmDto>> =
      withContext(Dispatchers.IO) {
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val config = configFor(night)
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
  suspend fun requestManual(night: Night, algorithmId: String): Result<Unit> =
      withContext(Dispatchers.IO) {
        val server = night.remoteId
            ?: return@withContext Result.failure(IllegalStateException("this night is not uploaded"))
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val started = AnalysisService.startManual(api, server, configFor(night), listOf(algorithmId))
          if (started.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("no compatible algorithm is installed")
            )
          }
          for (run in started) {
            uploads.markManualAnalysis(night.key, run.analysisId)
            uploads.markProcessing(night.key, run.analysisId)
          }
          refreshNight(night.key, server, started.first().analysisId)
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
  fun selectAnalysis(night: Night, analysisId: String) {
    uploads.markSelectedAnalysis(night.key, analysisId)
  }

  /**
   * Refresh one night's recording and its analyses, once. The fetched analysis list replaces the
   * cached run summaries for this process, so several analyses are visible immediately, and the
   * night's stored state, the result cache and the cached History list follow what the server says
   * (see [AnalysisSync]). The selected analysis is read when it is still listed.
   */
  suspend fun refreshNight(
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
                              nights.value.firstOrNull { it.key == key }?.analysisId,
                              uploads.record(key)?.analysisId,
                          ),
                  )
          val runs = snapshot.analyses.map { run -> run.toRun(downloaded = results.has(key, run.id)) }
          overrides.update { it + (key to NightRemoteOverride(snapshot.recording, runs)) }
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
          buildNights(
              localSessions = SessionStore.of(context).list(),
              records = records.mapValues { it.value.toNightRecord() },
              remote = remoteSummaries.value,
              overrides = overrides.value,
          )
      for (night in pendingRecordings(current, records)) {
        if (night.recordingId in pendingDeletes) continue
        refreshNight(night.key, night.recordingId, night.analysisId)
      }
    }
  }

  /** One recording changed on the server: read it and its analyses. */
  override suspend fun refreshRecording(recordingId: String) {
    withContext(Dispatchers.IO) {
      // A recording the user deleted is only waiting for the server to say so.
      if (recordingId in PendingDeleteStore.of(context).remoteIds()) return@withContext
      refreshNight(keyFor(recordingId), recordingId, null)
    }
  }

  /** The night key of a recording: the phone's own id when it has the recording, else the remote key. */
  private fun keyFor(recordingId: String): String =
      if (SessionStore.of(context).dir(recordingId).isDirectory) recordingId
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
    private val HR_ONLY = SessionConfig(hr = true, ecg = false, acc = false)

    @Volatile private var instance: NightRepository? = null

    fun of(context: Context): NightRepository =
        instance
            ?: synchronized(this) {
              instance ?: NightRepository(context).also { instance = it }
            }
  }
}

/**
 * The legacy upload record still persists the app state. This adapter is the only place that knows
 * about it; replacing the store later must not change the UI or [Night].
 */
internal fun UploadRecord.toNightRecord(): NightRecord {
  val backup =
      when (phase) {
        UploadPhase.LOCAL -> BackupState()
        UploadPhase.QUEUED ->
            BackupState(
                phase = BackupPhase.QUEUED,
                failure = failure,
                detail = errorDetail,
            )
        UploadPhase.UPLOADING ->
            BackupState(
                phase = BackupPhase.UPLOADING,
                progress = progress,
                failure = failure,
                detail = errorDetail,
            )
        UploadPhase.PROCESSING,
        UploadPhase.DONE ->
            BackupState(
                phase = BackupPhase.BACKED_UP,
                progress = 1f,
                warning = warning,
                uploadedAtMs = uploadedAtMs ?: updatedAtMs,
            )
        UploadPhase.FAILED ->
            if (failure == UploadFailure.ANALYSIS && analysisId != null) {
              BackupState(
                  phase = BackupPhase.BACKED_UP,
                  progress = 1f,
                  warning = warning,
                  uploadedAtMs = uploadedAtMs ?: updatedAtMs,
              )
            } else {
              BackupState(
                  phase = BackupPhase.FAILED,
                  failure = failure ?: UploadFailure.SERVER,
                  detail = errorDetail,
                  warning = warning,
              )
            }
      }
  val runStatus =
      when (phase) {
        UploadPhase.DONE -> AnalysisStatus.DONE
        UploadPhase.FAILED -> if (failure == UploadFailure.ANALYSIS) AnalysisStatus.FAILED else null
        UploadPhase.PROCESSING -> AnalysisStatus.RUNNING
        else -> null
      }
  val runs =
      if (analysisId != null && runStatus != null) {
        listOf(
            AnalysisRun(
                id = analysisId,
                algorithm = model.orEmpty(),
                version = modelVersion.orEmpty(),
                status = runStatus,
                error = errorDetail.takeIf { failure == UploadFailure.ANALYSIS },
                epochSeconds = epochSeconds,
                segments = segments,
                sleepScore = sleepScore,
                updatedAtMs = updatedAtMs,
                resultDownloaded = resultDownloaded,
            )
        )
      } else {
        emptyList()
      }
  return NightRecord(
      id = id,
      // Records written before the id was shared with the server have no flag, but a night that
      // reached processing or has an analysis was uploaded.
      onServer =
          onServer ||
              phase == UploadPhase.PROCESSING ||
              phase == UploadPhase.DONE ||
              analysisId != null,
      backup = backup,
      analyses = runs,
      selectedAnalysisId = analysisId,
      keepRawOnPhone = keepRawOnPhone,
      manualAnalysisIds = manualAnalysisIds,
      updatedAtMs = updatedAtMs,
  )
}
