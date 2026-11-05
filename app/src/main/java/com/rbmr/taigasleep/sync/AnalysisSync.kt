package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException

/** One recording as the server has it right now, and the analysis its night shows. */
class RecordingSnapshot(
    val recording: RecordingDto,
    val analyses: List<AnalysisDto>,
    /**
     * The analysis the night shows, with its full result when it succeeded. Null when the recording
     * has no analysis.
     */
    val shown: AnalysisDto?,
)

/**
 * Brings the app's stored view of one recording in line with the server, once. This is what runs
 * when the event stream says a recording changed, when it (re)connects, and when a night is opened.
 * It is a plain read-and-store, never a loop: nothing waits for an analysis to finish.
 *
 * The analysis a night shows is [chooseAnalysis]. A succeeded one has its full result saved in the
 * [ResultCache] and the night becomes done; a failed one marks the night failed; one that is queued
 * or running leaves it processing. A night whose analysis is gone (deleted, or replaced by a retry
 * under a new id) follows what the recording lists now.
 */
class AnalysisSync(
    private val client: TaigaClient,
    private val store: UploadStore,
    private val results: ResultCache,
) {
  /**
   * Reads [recordingId] and writes what it says for the night [key]. [preferred] are analysis ids in
   * order of preference, for example the one the user selected; ids the server no longer lists are
   * skipped. A [TaigaHttpException] (a 404 means the recording itself is gone) and the other
   * transport failures are the caller's to handle.
   */
  suspend fun refresh(
      key: String,
      recordingId: String,
      preferred: List<String?> = emptyList(),
  ): RecordingSnapshot {
    val recording = client.getRecording(recordingId)
    var analyses = client.listAnalyses(recordingId).items

    var cached = false
    var shown: AnalysisDto?
    while (true) {
      val chosen = chooseAnalysis(analyses, preferred)
      // A list leaves the result out. A result never changes once it succeeded, so one that is on
      // this phone is not downloaded again.
      shown =
          try {
            when {
              chosen == null || !chosen.isSucceeded -> chosen
              else ->
                  results.load(key, chosen.id)?.takeIf { it.isSucceeded && it.result != null }?.also {
                    cached = true
                  } ?: client.getAnalysis(chosen.id)
            }
          } catch (e: TaigaHttpException) {
            // It was deleted after the list was read. The recording is still there, so choose again
            // among what is left; a 404 that leaves this function is the recording's own.
            if (e.status != 404 || chosen == null) throw e
            analyses = analyses.filterNot { it.id == chosen.id }
            continue
          }
      break
    }

    when {
      shown == null -> {
        // A sealed recording without an analysis is waiting for one to be requested.
        if (recording.complete) store.markProcessing(key)
      }
      shown.isSucceeded -> {
        // A result never changes once it succeeded, so one already on this phone is not saved again.
        if (!cached) results.save(key, shown.id, shown)
        store.markDone(key)
      }
      shown.isFailed ->
          store.markFailed(key, UploadFailure.ANALYSIS, shown.error.takeIf { it.isNotBlank() })
      else -> if (recording.complete) store.markProcessing(key)
    }
    return RecordingSnapshot(recording, analyses, shown)
  }
}

/**
 * The analysis of a recording to report on: the first of [preferred] the server lists, otherwise
 * the newest finished run, otherwise the newest run of any kind. Null when there is none. A remembered
 * id can be gone, so an id that is not listed is never chosen.
 */
internal fun chooseAnalysis(analyses: List<AnalysisDto>, preferred: List<String?>): AnalysisDto? =
    preferred.firstNotNullOfOrNull { id -> analyses.firstOrNull { it.id == id } }
        ?: analyses.filter { it.isSucceeded }.maxByOrNull { it.updated }
        ?: analyses.maxByOrNull { it.created }
