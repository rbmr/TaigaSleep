package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.parseServerTime
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingPhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a catch-up runs. Only [RECORDING_SAVED] and [UPLOAD_FINISHED] name one recording. */
internal enum class CatchUpReason {
  /** A recording finished and its files are saved: it may need an upload. */
  RECORDING_SAVED,
  /** An upload finished: the recording may need an analysis. */
  UPLOAD_FINISHED,
  /** An automatic switch was changed. Switching one off also stops the queued automatic uploads. */
  SETTING_CHANGED,
  /** Wi-Fi only or charging only changed: the queued automatic uploads are queued again. */
  UPLOAD_CONDITIONS_CHANGED,
  APP_START,
  APP_RESUME,
  SERVER_CONNECTED,
  ACCOUNT_CHANGED,
  /** The safety net: nothing happened, but something may have been missed. */
  PERIODIC,
}

/** How long a server failure waits before the automatic upload tries the night again. */
internal const val SERVER_FAILURE_COOLDOWN_MS = 6 * 60 * 60 * 1000L

/** How long a failed analysis waits before it is asked for again. */
internal const val ANALYSIS_RETRY_COOLDOWN_MS = 6 * 60 * 60 * 1000L

/** How often one failed analysis is asked for again automatically, on top of the server's own tries. */
internal const val ANALYSIS_AUTO_RETRIES = 2

/** One recording on this phone as the automatic upload sees it. */
internal data class UploadNight(
    val id: String,
    val state: RecordingPhase,
    /** The recorder still owns the files: the service runs, or the phone marks it as live. */
    val live: Boolean,
    /** What the active account's rows say, null when there is none. */
    val record: UploadRecord?,
)

/**
 * The nights the automatic upload has to queue now. A night is due when it is finished (a night that
 * is recording or still on the tracker is not), and the active account does not have it: it was
 * never queued, it waits or stopped in the middle, or its last attempt failed for a reason that
 * waiting can fix. A failure that waiting does not fix (a full disk, a refused recording) is the
 * user's to retry.
 */
internal fun uploadsDue(
    nights: List<UploadNight>,
    settings: AutoUploadSettings,
    nowMs: Long,
): List<String> = if (!settings.enabled) emptyList() else nights.filter { uploadDue(it, nowMs) }.map { it.id }

internal fun uploadDue(night: UploadNight, nowMs: Long): Boolean {
  val finished =
      when (night.state) {
        RecordingPhase.FETCH_PENDING -> false
        RecordingPhase.RECORDING -> !night.live // a crash left it open; the upload closes it
        else -> true
      }
  if (!finished) return false
  val record = night.record ?: return true
  return when (record.phase) {
    UploadPhase.LOCAL,
    UploadPhase.QUEUED,
    UploadPhase.UPLOADING -> true
    UploadPhase.UPLOADED,
    UploadPhase.PROCESSING,
    UploadPhase.DONE -> false
    UploadPhase.FAILED ->
        when (record.failure) {
          UploadFailure.NETWORK,
          UploadFailure.LOGIN,
          UploadFailure.RECORDING,
          UploadFailure.VERSION -> true
          UploadFailure.SERVER -> nowMs - record.updatedAtMs >= SERVER_FAILURE_COOLDOWN_MS
          UploadFailure.STORAGE,
          UploadFailure.ANALYSIS,
          null -> false
        }
  }
}

/** One recording the active account has on the server, as the automatic analysis sees it. */
internal data class AnalysisNight(
    /** The recording key of the library. */
    val key: String,
    val recordingId: String,
    val record: UploadRecord?,
    /** Null when the streams of the recording are not known; such a night is left alone. */
    val config: RecordingConfig?,
    /** The server says the recording is complete, as the saved list has it. */
    val complete: Boolean,
    /** The runs the saved list has; the server is asked again before anything is requested. */
    val cachedRuns: List<AnalysisDto>,
)

/** What the automatic analysis does for one night. */
internal sealed interface AnalysisAction {
  data object None : AnalysisAction

  /** Ask for the first compatible method of the preferred order. */
  data class Start(val choice: StagerChoice) : AnalysisAction

  /** Ask again for a run that failed for a reason that may have passed. */
  data class Retry(val failed: AnalysisDto) : AnalysisAction
}

/**
 * A failure the worker names with a code (`missing_stream: ...`) is about the recording itself and
 * asking again repeats it. One without a code (a timeout, a crashed job, an expired lease) is about
 * the worker.
 */
internal fun AnalysisDto.failedForGood(): Boolean = CODED_ERROR.containsMatchIn(error)

private val CODED_ERROR = Regex("^[a-z][a-z0-9_]*:")

/**
 * What the automatic analysis does for a night with these [runs]. Nothing when automatic analysis is
 * off, when any run is waiting, running or done, or when the only runs failed for a lasting reason.
 * A night with no run at all gets the first method of the preferred order that is installed and that
 * the recording can feed. When every run failed for a passing reason, the latest one is asked again
 * once its cooldown has passed and while [retriesUsed] allows it.
 */
internal fun analysisAction(
    runs: List<AnalysisDto>,
    config: RecordingConfig,
    installed: List<AlgorithmDto>,
    settings: AnalysisSettings,
    retriesUsed: (AnalysisDto) -> Int,
    nowMs: Long,
): AnalysisAction {
  if (!settings.auto) return AnalysisAction.None
  if (runs.any { !it.isFailed }) return AnalysisAction.None
  if (runs.isEmpty()) {
    val choice = selectStagers(config, installed, settings.preferenceOrder).firstOrNull()
    return choice?.let(AnalysisAction::Start) ?: AnalysisAction.None
  }
  if (runs.any { it.failedForGood() }) return AnalysisAction.None
  val latest = runs.maxBy { it.created }
  val failedAt = parseServerTime(latest.finished_at ?: latest.updated)?.toEpochMilli()
  if (failedAt != null && nowMs - failedAt < ANALYSIS_RETRY_COOLDOWN_MS) return AnalysisAction.None
  if (retriesUsed(latest) >= ANALYSIS_AUTO_RETRIES) return AnalysisAction.None
  return AnalysisAction.Retry(latest)
}

/** Whether the saved state says this night may need an analysis, so the server is worth asking. */
internal fun mayNeedAnalysis(
    night: AnalysisNight,
    installed: List<AlgorithmDto>,
    settings: AnalysisSettings,
    nowMs: Long,
): Boolean {
  val config = night.config ?: return false
  val phase = night.record?.phase
  if (phase == UploadPhase.UPLOADED) return true
  if (phase == UploadPhase.FAILED && night.record?.failure == UploadFailure.ANALYSIS) return true
  if (phase == UploadPhase.PROCESSING || phase == UploadPhase.DONE) return false
  if (!night.complete) return false
  return analysisAction(night.cachedRuns, config, installed, settings, { 0 }, nowMs) !=
      AnalysisAction.None
}

/** What [Automation] needs from the app: the nights, the queues, the server and the settings. */
internal interface AutomationEnvironment {
  /** The active account, null while there is no session. */
  val account: String?
  val autoUpload: AutoUploadSettings
  val analysis: AnalysisSettings

  fun nowMs(): Long

  /** The finished and unfinished recordings on this phone ([only] limits it to one). */
  suspend fun uploadNights(only: String?): List<UploadNight>

  /** Queue one automatic upload; [replace] queues it again with the conditions as they are now. */
  suspend fun queueUpload(id: String, replace: Boolean)

  /** Stop the queued and running automatic uploads. A manual upload carries on. */
  suspend fun stopAutoUploads()

  suspend fun analysisNights(only: String?): List<AnalysisNight>

  fun session(): RemoteSession?

  /** The installed algorithms, or null when the server did not answer. */
  suspend fun installed(session: RemoteSession): List<AlgorithmDto>?

  /** The runs the server lists for a recording, or null when it did not answer. */
  suspend fun runs(session: RemoteSession, recordingId: String): List<AnalysisDto>?

  /** Ask for one analysis and mark the night processing. [retry] is the failed run asked for again. */
  suspend fun requestAnalysis(
      session: RemoteSession,
      night: AnalysisNight,
      choice: StagerChoice,
      retry: AnalysisDto?,
  )

  fun sessionRejected(session: RemoteSession)

  fun retriesUsed(recordingId: String, analysis: AnalysisDto): Int
}

/**
 * The two automations, upload and analysis, as the same kind of thing: a catch-up. Each looks for all
 * the work it has not done yet and does it, so neither depends on an event having been seen. A catch-up
 * is idempotent and cheap: an upload is queued as unique work per recording and only for a night
 * that is due, and an analysis is only requested after the server has been asked what the night
 * already has.
 *
 * [run] is what every trigger calls (see [watch] for the ones that come from state, and
 * [SyncService] for the ones that come from the recording and the upload). Catch-ups run one after
 * the other; a full one that is already waiting absorbs the next.
 */
internal class Automation(private val environment: AutomationEnvironment) {
  private val mutex = Mutex()
  private val fullWaiting = AtomicBoolean(false)

  /** Do what is due. [only] limits the check to one recording; null checks every recording. */
  suspend fun run(reason: CatchUpReason, only: String? = null) {
    if (only == null && !fullWaiting.compareAndSet(false, true)) return
    mutex.withLock {
      if (only == null) fullWaiting.set(false)
      try {
        if (environment.account == null) return
        catchUpUploads(reason, only)
        catchUpAnalyses(only)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // The next trigger tries again; a catch-up never takes anything else down.
      }
    }
  }

  private suspend fun catchUpUploads(reason: CatchUpReason, only: String?) {
    val settings = environment.autoUpload
    if (!settings.enabled) {
      if (reason == CatchUpReason.SETTING_CHANGED) environment.stopAutoUploads()
      return
    }
    val due = uploadsDue(environment.uploadNights(only), settings, environment.nowMs())
    for (id in due) {
      environment.queueUpload(id, replace = reason == CatchUpReason.UPLOAD_CONDITIONS_CHANGED)
    }
  }

  private suspend fun catchUpAnalyses(only: String?) {
    val settings = environment.analysis
    if (!settings.auto) return
    val session = environment.session() ?: return
    val now = environment.nowMs()
    val nights = environment.analysisNights(only)
    if (nights.isEmpty()) return
    val installed = environment.installed(session) ?: return
    for (night in nights) {
      if (!mayNeedAnalysis(night, installed, settings, now)) continue
      if (environment.account != session.account) return
      val config = night.config ?: continue
      try {
        // The saved list can be behind, so what the night has is asked of the server.
        val runs = environment.runs(session, night.recordingId) ?: continue
        val action =
            analysisAction(
                runs,
                config,
                installed,
                settings,
                { environment.retriesUsed(night.recordingId, it) },
                now,
            )
        when (action) {
          AnalysisAction.None -> Unit
          is AnalysisAction.Start -> environment.requestAnalysis(session, night, action.choice, null)
          is AnalysisAction.Retry ->
              environment.requestAnalysis(
                  session,
                  night,
                  StagerChoice(action.failed.algorithm, action.failed.version),
                  action.failed,
              )
        }
      } catch (e: TaigaUnauthorizedException) {
        environment.sessionRejected(session)
        return
      } catch (_: TaigaHttpException) {
        // Refused for this night (no worker offers the method yet): the next catch-up asks again.
      }
    }
  }

  /**
   * Turn what changes in the app into catch-ups: a switch of the automatic upload or analysis, the
   * server coming back, and another account. The first value of each flow is the state at start and
   * is not a change; [SyncService.start] covers it with a catch-up for the start itself.
   */
  fun watch(
      scope: CoroutineScope,
      uploadSettings: Flow<AutoUploadSettings>,
      analysisAuto: Flow<Boolean>,
      accounts: Flow<String?>,
      connected: Flow<Boolean>,
      onTrigger: suspend (CatchUpReason) -> Unit,
  ) {
    var previous: AutoUploadSettings? = null
    uploadSettings
        .map { now ->
          val before = previous
          previous = now
          when {
            before == null -> null
            before.enabled != now.enabled -> CatchUpReason.SETTING_CHANGED
            before != now -> CatchUpReason.UPLOAD_CONDITIONS_CHANGED
            else -> null
          }
        }
        .filter { it != null }
        .onEach { onTrigger(it!!) }
        .launchIn(scope)
    analysisAuto
        .distinctUntilChanged()
        .drop(1)
        .onEach { onTrigger(CatchUpReason.SETTING_CHANGED) }
        .launchIn(scope)
    accounts
        .distinctUntilChanged()
        .drop(1)
        .filter { it != null }
        .onEach { onTrigger(CatchUpReason.ACCOUNT_CHANGED) }
        .launchIn(scope)
    connected
        .distinctUntilChanged()
        .drop(1)
        .filter { it }
        .onEach { onTrigger(CatchUpReason.SERVER_CONNECTED) }
        .launchIn(scope)
  }
}
