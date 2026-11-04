package com.rbmr.taigasleep.night

import android.content.Context
import com.rbmr.taigasleep.analysis.AnalysisService
import com.rbmr.taigasleep.analysis.compatible
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.SessionListDto
import com.rbmr.taigasleep.remote.TaigaUnauthorizedException
import com.rbmr.taigasleep.storage.SessionConfig
import com.rbmr.taigasleep.storage.SessionMeta
import com.rbmr.taigasleep.upload.RemoteHistoryCache
import com.rbmr.taigasleep.upload.ResultCache
import com.rbmr.taigasleep.upload.UploadFailure
import com.rbmr.taigasleep.upload.UploadPhase
import com.rbmr.taigasleep.upload.UploadRecord
import com.rbmr.taigasleep.upload.UploadScheduler
import com.rbmr.taigasleep.upload.UploadStore
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
class NightRepository private constructor(context: Context) {
  private val context = context.applicationContext
  private val remote = RemoteRepository.of(this.context)
  private val uploads = UploadStore.of(this.context)
  private val history = RemoteHistoryCache.of(this.context)
  private val results = ResultCache.of(this.context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val remoteSummaries = MutableStateFlow<SessionListDto?>(null)
  private val overrides = MutableStateFlow<Map<String, NightRemoteOverride>>(emptyMap())
  private var loadedAccount: String? = null

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
      // A session detail belongs to one account; never show it after switching accounts.
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

  /** Fetch the server's session list and cache it. Does not touch local recordings. */
  suspend fun refreshList(): Result<Unit> =
      withContext(Dispatchers.IO) {
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val list = api.listSessions()
          history.save(remote.accountKey, list)
          remoteSummaries.value = list
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
          Result.success(api.getAlgorithms().algorithms.filter { compatible(it, config) })
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /** Request one explicit analysis, remember that it was manual, and start polling it. */
  suspend fun requestManual(night: Night, algorithmId: String): Result<Unit> =
      withContext(Dispatchers.IO) {
        val server = night.remoteId
            ?: return@withContext Result.failure(IllegalStateException("this night is not uploaded"))
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val started = AnalysisService.startManual(api, server, configFor(night), listOf(algorithmId))
          val runs = started.filter { it.analysisId != null }
          if (runs.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("no compatible algorithm is installed")
            )
          }
          for (run in runs) {
            val analysisId = run.analysisId ?: continue
            uploads.markManualAnalysis(night.key, analysisId)
            uploads.markProcessing(night.key, server, analysisId, run.jobId)
            UploadScheduler.enqueuePoll(
                context,
                night.localId ?: night.key,
                server,
                analysisId,
                run.jobId,
                replace = true,
            )
          }
          refreshNight(night.key, server, runs.first().analysisId)
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
   * Refresh one night's session and its selected analysis. The fetched detail replaces the cached
   * run summaries for this process, so several analyses are visible immediately.
   */
  suspend fun refreshNight(
      key: String,
      remoteId: String?,
      analysisId: String?,
  ): Result<AnalysisDto?> =
      withContext(Dispatchers.IO) {
        val record = uploads.record(key)
        val server = remoteId ?: record?.remoteId ?: return@withContext Result.success(null)
        val api =
            remote.api() ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val detail = api.getSession(server)
          val target =
              analysisId
                  ?: nights.value.firstOrNull { it.key == key }?.analysisId
                  ?: record?.analysisId
                  ?: detail.analyses.filter { it.status == "done" }.maxByOrNull { it.updated }?.id
                  ?: detail.analyses.maxByOrNull { it.created }?.id
          val analysis = target?.let { api.getAnalysis(it) }
          val jobId =
              detail.jobs.firstOrNull { it.analysis == (target ?: record?.analysisId) }?.id
          val runs =
              detail.analyses.map { run ->
                run.toRun(
                    jobId = detail.jobs.firstOrNull { it.analysis == run.id }?.id,
                    downloaded = results.has(key, run.id),
                )
              }
          overrides.update { it + (key to NightRemoteOverride(detail.session, runs)) }

          if (analysis?.status == "done") {
            val downloaded = results.save(key, analysis.id, analysis)
            uploads.markDone(
                id = key,
                remoteId = detail.session.id,
                analysisId = analysis.id,
                jobId = jobId,
                model = analysis.model,
                modelVersion = analysis.model_version,
                epochSeconds = analysis.stages?.epoch_seconds ?: 30,
                segments = analysis.stages?.segments.orEmpty(),
                sleepScore = analysis.sleep_quality?.score,
                resultDownloaded = downloaded,
            )
          } else if (analysis?.status == "failed") {
            uploads.markFailed(key, UploadFailure.ANALYSIS, analysis.error)
          } else if (detail.session.upload_status == "uploaded") {
            uploads.markProcessing(key, detail.session.id, target, jobId)
          } else if (detail.session.upload_status == "failed") {
            uploads.markFailed(key, UploadFailure.SERVER, detail.session.upload_error)
          }
          Result.success(analysis)
        } catch (e: TaigaUnauthorizedException) {
          remote.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
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
            if (remoteId != null && failure == UploadFailure.ANALYSIS && analysisId != null) {
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
                jobId = jobId,
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
      remoteId = remoteId,
      backup = backup,
      analyses = runs,
      selectedAnalysisId = analysisId,
      keepRawOnPhone = keepRawOnPhone,
      manualAnalysisIds = manualAnalysisIds,
      updatedAtMs = updatedAtMs,
  )
}
