package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.recording.store.RecordingConfig
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** The server's newly requested run. */
data class StartedAnalysis(
    val analysisId: String,
    val status: String,
)

/** Manual choices ignore the automatic setting and the configured order. */
fun manualChoices(
    config: RecordingConfig,
    algorithms: List<AlgorithmDto>,
    ids: List<String>,
): List<StagerChoice> = selectStagers(config, algorithms, ids)

/**
 * How one analysis is selected and sent. [AnalysisRequester] is the one way a path asks for an
 * analysis; this is the selection and transport behind it. Uploading and requesting are separate: a
 * recording can be safely on the server with no analysis, and manual requests can add more later.
 *
 * The personal data and the start date only reach SleepECG: its classifiers use age, sex and weight
 * as model features. An empty profile sends no parameters, and the other algorithms never get any.
 */
internal object AnalysisService {
  /** The preferred compatible method; a request is always an explicit decision, not a setting. */
  suspend fun startPreferred(
      client: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      settings: AnalysisSettings,
      personalData: PersonalData = PersonalData(),
      startDate: LocalDate? = null,
  ): StartedAnalysis {
    val choice = selectStager(config, installed(client), settings.preferenceOrder)
    return request(client, recordingId, choice, personalData, startDate)
  }

  /** Ask for one or more explicit stagers. Unknown or incompatible ids are skipped. */
  suspend fun startManual(
      client: TaigaClient,
      recordingId: String,
      config: RecordingConfig,
      ids: List<String>,
      personalData: PersonalData = PersonalData(),
      startDate: LocalDate? = null,
  ): List<StartedAnalysis> {
    val choices = manualChoices(config, installed(client), ids)
    return choices.map { request(client, recordingId, it, personalData, startDate) }
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

  /** The list a choice needs: a request fails rather than guess a method the server never named. */
  private suspend fun installed(client: TaigaClient): List<AlgorithmDto> =
      installedAlgorithms(client).getOrThrow()

  /** One analysis per call; asking again for the same run returns the one that exists. */
  suspend fun request(
      client: TaigaClient,
      recordingId: String,
      choice: StagerChoice,
      personalData: PersonalData,
      startDate: LocalDate?,
  ): StartedAnalysis {
    val analysis =
        client.requestAnalysis(
            recordingId,
            choice.id,
            choice.version,
            analysisParams(choice.id, personalData, startDate),
        )
    return StartedAnalysis(analysisId = analysis.id, status = analysis.status)
  }

  /** Ask again for a run that failed, with the parameters it was asked for with. */
  suspend fun retry(
      client: TaigaClient,
      recordingId: String,
      failed: AnalysisDto,
  ): StartedAnalysis {
    val analysis =
        client.requestAnalysis(recordingId, failed.algorithm, failed.version, failed.params)
    return StartedAnalysis(analysisId = analysis.id, status = analysis.status)
  }
}
