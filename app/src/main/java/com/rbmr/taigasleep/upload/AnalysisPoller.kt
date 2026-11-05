package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.TaigaClient
import com.rbmr.taigasleep.remote.TaigaHttpException

sealed interface PollResult {
  data class Done(val analysis: AnalysisDto) : PollResult

  data class Pending(val analysisId: String?) : PollResult

  data class Failed(val detail: String?) : PollResult
}

/**
 * Reads one analysis and persists its state. Polling is separate from uploading so a slow or
 * offline worker never holds the transfer notification open.
 */
class AnalysisPoller(
    private val client: TaigaClient,
    private val store: UploadStore,
    private val cache: ResultCache,
    private val key: String,
) {
  suspend fun poll(recordingId: String, analysisId: String?): PollResult {
    val analysis = client.currentAnalysis(recordingId, analysisId)

    if (analysis == null) {
      store.markProcessing(key, null)
      return PollResult.Pending(analysisId = null)
    }

    return when {
      analysis.isSucceeded -> {
        val saved = cache.save(key, analysis.id, analysis)
        store.markDone(
            id = key,
            analysisId = analysis.id,
            model = analysis.algorithm,
            modelVersion = analysis.version,
            epochSeconds = analysis.result?.stages?.epoch_seconds ?: 30,
            segments = analysis.result?.stages?.segments.orEmpty(),
            sleepScore = analysis.result?.sleep_quality?.score,
            resultDownloaded = saved,
        )
        PollResult.Done(analysis)
      }
      analysis.isFailed -> {
        val message = analysis.error.takeIf { it.isNotBlank() }
        store.markFailed(key, UploadFailure.ANALYSIS, message)
        PollResult.Failed(message)
      }
      else -> {
        store.markProcessing(key, analysis.id)
        PollResult.Pending(analysis.id)
      }
    }
  }
}

/**
 * The analysis of [recordingId] to report on: the one with [analysisId], otherwise the newest
 * finished run, otherwise the newest run of any kind. Null when the recording has none yet. A run
 * that succeeded is returned with its full result.
 *
 * An analysis can be gone: deleted, or replaced by a retry under a new id. Then the recording's own
 * list says what exists now.
 */
internal suspend fun TaigaClient.currentAnalysis(
    recordingId: String,
    analysisId: String?,
): AnalysisDto? {
  val known =
      analysisId?.let {
        try {
          getAnalysis(it)
        } catch (e: TaigaHttpException) {
          if (e.status == 404) null else throw e
        }
      }
  val analysis = known ?: pick(listAnalyses(recordingId).items) ?: return null
  // A list leaves the result out, so a run found there is fetched whole.
  return if (analysis.isSucceeded && analysis.result == null) getAnalysis(analysis.id) else analysis
}

private fun pick(analyses: List<AnalysisDto>): AnalysisDto? =
    analyses.filter { it.isSucceeded }.maxByOrNull { it.updated }
        ?: analyses.maxByOrNull { it.created }
