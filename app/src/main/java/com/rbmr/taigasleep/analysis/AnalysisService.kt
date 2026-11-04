package com.rbmr.taigasleep.analysis

import com.rbmr.taigasleep.remote.AlgorithmDto
import com.rbmr.taigasleep.remote.TaigaClient
import com.rbmr.taigasleep.storage.SessionConfig
import kotlinx.coroutines.CancellationException

/** The server's newly requested run, or null ids when the response carried no analysis. */
data class StartedAnalysis(
    val analysisId: String?,
    val jobId: String?,
    val status: String?,
)

/** The automatic choice is null when automatic analysis is off. */
fun autoChoice(
    config: SessionConfig,
    algorithms: List<AlgorithmDto>,
    settings: AnalysisSettings,
): StagerChoice? =
    if (settings.auto) selectStager(config, algorithms, settings.preferenceOrder) else null

/** Manual choices ignore the automatic setting and the configured order. */
fun manualChoices(
    config: SessionConfig,
    algorithms: List<AlgorithmDto>,
    ids: List<String>,
): List<StagerChoice> = selectStagers(config, algorithms, ids)

/**
 * The one place that asks the server for analyses. Uploading and requesting are separate: a night
 * can be safely on the server with no analysis, and manual requests can add more later.
 */
object AnalysisService {
  /** After an upload: request the preferred compatible stager, or nothing when auto is off. */
  suspend fun startAuto(
      client: TaigaClient,
      sessionId: String,
      config: SessionConfig,
      settings: AnalysisSettings,
  ): StartedAnalysis? {
    if (!settings.auto) return null
    val choice = autoChoice(config, installed(client), settings) ?: return null
    return request(client, sessionId, choice)
  }

  /** A manual retry of a night that has no analysis yet, regardless of the automatic setting. */
  suspend fun startPreferred(
      client: TaigaClient,
      sessionId: String,
      config: SessionConfig,
      settings: AnalysisSettings,
  ): StartedAnalysis {
    val choice = selectStager(config, installed(client), settings.preferenceOrder)
    return request(client, sessionId, choice)
  }

  /** Ask for one or more explicit stagers. Unknown or incompatible ids are skipped. */
  suspend fun startManual(
      client: TaigaClient,
      sessionId: String,
      config: SessionConfig,
      ids: List<String>,
  ): List<StartedAnalysis> {
    val choices = manualChoices(config, installed(client), ids)
    return choices.map { request(client, sessionId, it) }
  }

  private suspend fun installed(client: TaigaClient): List<AlgorithmDto> =
      try {
        client.getAlgorithms().algorithms
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // An unreachable algorithm endpoint must not block the request: selectStager falls back.
        emptyList()
      }

  private suspend fun request(
      client: TaigaClient,
      sessionId: String,
      choice: StagerChoice,
  ): StartedAnalysis {
    val response = client.requestAnalysis(sessionId, choice.id, choice.version)
    return StartedAnalysis(
        analysisId = response.analyses.firstOrNull()?.id,
        jobId = response.jobs.firstOrNull()?.id,
        status = response.analyses.firstOrNull()?.status,
    )
  }
}
