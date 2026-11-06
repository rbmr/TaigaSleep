package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.store.RecordingConfig
import kotlinx.coroutines.CancellationException

/** The server's newly requested run. */
data class StartedAnalysis(
    val analysisId: String,
    val status: String,
)

/** The automatic choice is null when automatic analysis is off. */
fun autoChoice(
    config: RecordingConfig,
    algorithms: List<AlgorithmDto>,
    settings: AnalysisSettings,
): StagerChoice? =
    if (settings.auto) selectStager(config, algorithms, settings.preferenceOrder) else null

/** Manual choices ignore the automatic setting and the configured order. */
fun manualChoices(
    config: RecordingConfig,
    algorithms: List<AlgorithmDto>,
    ids: List<String>,
): List<StagerChoice> = selectStagers(config, algorithms, ids)

/**
 * The one place that asks the server for analyses. Uploading and requesting are separate: a recording
 * can be safely on the server with no analysis, and manual requests can add more later.
 */
internal object AnalysisService {
  /** After an upload: request the preferred compatible stager, or nothing when auto is off. */
  suspend fun startAuto(
      client: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      settings: AnalysisSettings,
  ): StartedAnalysis? {
    if (!settings.auto) return null
    val choice = autoChoice(config, installed(client), settings) ?: return null
    return request(client, recordingId, choice)
  }

  /** A manual retry of a recording that has no analysis yet, regardless of the automatic setting. */
  suspend fun startPreferred(
      client: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      settings: AnalysisSettings,
  ): StartedAnalysis {
    val choice = selectStager(config, installed(client), settings.preferenceOrder)
    return request(client, recordingId, choice)
  }

  /** Ask for one or more explicit stagers. Unknown or incompatible ids are skipped. */
  suspend fun startManual(
      client: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      ids: List<String>,
  ): List<StartedAnalysis> {
    val choices = manualChoices(config, installed(client), ids)
    return choices.map { request(client, recordingId, it) }
  }

  /** The algorithms the server reports, or the failure to read them. */
  suspend fun installedAlgorithms(client: TaigaClient): Result<List<AlgorithmDto>> =
      try {
        Result.success(client.getAlgorithms().items)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }

  private suspend fun installed(client: TaigaClient): List<AlgorithmDto> =
      installedAlgorithms(client).getOrDefault(emptyList())

  /** One analysis per call; asking again for the same run returns the one that exists. */
  private suspend fun request(
      client: TaigaClient,
      recordingId: String,
      choice: StagerChoice,
  ): StartedAnalysis {
    val analysis = client.requestAnalysis(recordingId, choice.id, choice.version)
    return StartedAnalysis(analysisId = analysis.id, status = analysis.status)
  }
}
