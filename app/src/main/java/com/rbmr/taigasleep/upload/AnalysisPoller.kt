package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.SessionDetailDto
import com.rbmr.taigasleep.remote.TaigaClient

sealed interface PollResult {
  data class Done(val analysis: AnalysisDto) : PollResult

  data class Pending(val analysisId: String?, val jobId: String?) : PollResult

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
  suspend fun poll(
      remoteId: String,
      analysisId: String?,
      jobId: String?,
  ): PollResult {
    var resolvedJobId = jobId
    var detail: SessionDetailDto? = null
    var analysis =
        if (analysisId != null) {
          client.getAnalysis(analysisId)
        } else {
          val sessionDetail = client.getSession(remoteId)
          detail = sessionDetail
          sessionDetail.analyses
              .filter { it.status == "done" }
              .maxByOrNull { it.updated }
              ?: sessionDetail.analyses.maxByOrNull { it.created }
        }

    if (analysis == null) {
      val pendingJob = detail?.jobs?.firstOrNull { it.status == "queued" || it.status == "running" }
      store.markProcessing(key, remoteId, null, pendingJob?.id)
      return PollResult.Pending(analysisId = null, jobId = pendingJob?.id)
    }

    if (resolvedJobId == null) {
      val sessionDetail = detail ?: client.getSession(remoteId)
      detail = sessionDetail
      resolvedJobId = sessionDetail.jobs.firstOrNull { it.analysis == analysis.id }?.id
    }

    return when (analysis.status) {
      "done" -> {
        val saved = cache.save(key, analysis)
        store.markDone(
            id = key,
            remoteId = remoteId,
            analysisId = analysis.id,
            jobId = resolvedJobId,
            model = analysis.model,
            modelVersion = analysis.model_version,
            epochSeconds = analysis.stages?.epoch_seconds ?: 30,
            segments = analysis.stages?.segments.orEmpty(),
            qualityScore = analysis.quality?.score,
            resultDownloaded = saved,
        )
        PollResult.Done(analysis)
      }
      "failed" -> {
        val message = analysis.error.takeIf { it.isNotBlank() }
        store.markFailed(key, UploadFailure.ANALYSIS, message)
        PollResult.Failed(message)
      }
      else -> {
        store.markProcessing(key, remoteId, analysis.id, resolvedJobId)
        PollResult.Pending(analysis.id, resolvedJobId)
      }
    }
  }
}
