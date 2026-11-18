package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.parseServerTime
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.recordingStartDate
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/** Heart rate is the one stream every recording has, so it is the last answer for its streams. */
internal val HR_ONLY = RecordingConfig(hr = true, ecg = false, acc = false)

/** What a request knows about the recording before it asks: the streams it can feed and its date. */
internal data class AnalysisRecording(
    val config: RecordingConfig,
    /** The date it started on, which SleepECG takes the age on; null when neither side knows it. */
    val startDate: LocalDate?,
)

/**
 * What [AnalysisRequester] needs from the app: the recording as the phone and the server describe it,
 * the profile, and the state a request leaves behind.
 */
internal interface AnalysisRequestEnvironment {
  /** The recording's own meta.json, or null when the phone does not have the recording. */
  fun localMeta(key: String): RecordingMeta?

  /** The server's meta.json of a recording the phone does not have, or null when it cannot be read. */
  suspend fun serverMeta(api: TaigaClient, recordingId: String): JsonObject?

  /** The start time the server reported for the recording, when its meta cannot date it. */
  suspend fun serverStart(recordingId: String): String?

  /** The personal data the user entered, used only as optional SleepECG model features. */
  fun personalData(): PersonalData

  /** Mark the recording processing for [account]; nothing is written when the account changed. */
  suspend fun markProcessing(key: String, account: String)

  /** How often the automatic analysis has already asked for this run again. */
  fun retriesUsed(recordingId: String, analysis: AnalysisDto): Int

  /** Count one more automatic retry of this run. */
  fun countRetry(recordingId: String, analysis: AnalysisDto)
}

/**
 * The one way an analysis is asked for. The manual request, its retry, the automatic catch-up and the
 * catch-up after an upload all come through here, so one recording is described the same way and
 * asked for with the same parameters whichever path reached it.
 *
 * The configuration is the recording's own meta.json, then the server's, then heart rate only. The
 * profile and the start date only reach SleepECG: its classifiers use age, sex and weight as model
 * features.
 */
internal class AnalysisRequester(private val environment: AnalysisRequestEnvironment) {
  /** How often the automatic analysis has already asked for [analysis] again. */
  fun automaticRetriesUsed(recordingId: String, analysis: AnalysisDto): Int =
      environment.retriesUsed(recordingId, analysis)

  /** Ask for the first method of [settings]'s order that the recording can feed. */
  suspend fun startPreferred(
      session: RemoteSession,
      key: String,
      settings: AnalysisSettings,
  ): StartedAnalysis {
    val recording = recording(session.api, key)
    val started =
        AnalysisService.startPreferred(
            session.api,
            recordingIdOf(key),
            recording.config,
            settings,
            environment.personalData(),
            recording.startDate,
        )
    environment.markProcessing(key, session.account)
    return started
  }

  /** Ask for explicit methods. Unknown or incompatible ids are skipped and the order is kept. */
  suspend fun startManual(
      session: RemoteSession,
      key: String,
      ids: List<String>,
  ): List<StartedAnalysis> {
    val recording = recording(session.api, key)
    val started =
        AnalysisService.startManual(
            session.api,
            recordingIdOf(key),
            recording.config,
            ids,
            environment.personalData(),
            recording.startDate,
        )
    if (started.isNotEmpty()) environment.markProcessing(key, session.account)
    return started
  }

  /** Ask for a method the automatic catch-up picked. */
  suspend fun startChoice(
      session: RemoteSession,
      key: String,
      choice: StagerChoice,
  ): StartedAnalysis {
    val recording = recording(session.api, key)
    val started =
        AnalysisService.request(
            session.api,
            recordingIdOf(key),
            choice,
            environment.personalData(),
            recording.startDate,
        )
    environment.markProcessing(key, session.account)
    return started
  }

  /**
   * Ask again for a run that failed, with the parameters it was asked for with. [automatic] counts the
   * retry against the run's allowance; a retry the user started does not.
   */
  suspend fun retry(
      session: RemoteSession,
      key: String,
      failed: AnalysisDto,
      automatic: Boolean,
  ): StartedAnalysis {
    val recordingId = recordingIdOf(key)
    val started = AnalysisService.retry(session.api, recordingId, failed)
    if (automatic) environment.countRetry(recordingId, failed)
    environment.markProcessing(key, session.account)
    return started
  }

  /** The recording has a run on its way: mark it processing without asking for another. */
  suspend fun markProcessing(session: RemoteSession, key: String) {
    environment.markProcessing(key, session.account)
  }

  /** The recording as the phone and the server describe it, with one read of meta.json. */
  private suspend fun recording(api: TaigaClient, key: String): AnalysisRecording {
    val local = environment.localMeta(key)
    if (local != null) {
      return AnalysisRecording(local.config, recordingStartDate(local.startUtcMs, local.timezone))
    }
    val recordingId = recordingIdOf(key)
    val server = serverRecordingMeta(environment.serverMeta(api, recordingId))
    val startDate =
        server?.let { recordingStartDate(it.startUtcMs, it.timezone) }
            ?: serverStartDate(environment.serverStart(recordingId))
    return AnalysisRecording(server?.config ?: HR_ONLY, startDate)
  }

  /** The date the server's start time falls on in the phone's timezone; null when it is unknown. */
  private fun serverStartDate(started: String?): LocalDate? {
    val ms = parseServerTime(started)?.toEpochMilli() ?: return null
    return recordingStartDate(ms, null)
  }
}

/** The recording meta the server sent, or null when it carries none or cannot be read. */
internal fun serverRecordingMeta(meta: JsonObject?): RecordingMeta? =
    meta?.let { runCatching { RecordingMeta.fromJson(it) }.getOrNull() }
