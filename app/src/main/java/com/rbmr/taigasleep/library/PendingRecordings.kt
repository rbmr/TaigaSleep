package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord

/** A night that has to be read from the server again. [analysisId] is the run it shows. */
internal data class PendingRecording(
    val key: String,
    val recordingId: String,
    val analysisId: String?,
)

/**
 * The nights a reconcile has to read from the server: those whose analysis is not settled in the
 * app. [nights] is what the app shows after the History list was just fetched, [records] what it
 * stored per night key. A night on the server is pending when
 *
 * - one of its runs is queued or running;
 * - its stored sync state still says processing while the server lists a run: it may have
 *   finished or failed while the app was closed. A night uploaded with automatic analysis off has
 *   no run and is left alone;
 * - the run it shows succeeded and its result is not on this phone yet.
 *
 * A night the server has not sealed is still uploading and is not read.
 */
internal fun pendingRecordings(
    nights: List<RecordingView>,
    records: Map<String, UploadRecord>,
): List<PendingRecording> =
    nights.mapNotNull { night ->
      val recordingId = night.remoteId ?: return@mapNotNull null
      if (night.remote?.complete == false) return@mapNotNull null
      val record = records[night.key]
      val working =
          night.analyses.any {
            it.status == AnalysisStatus.QUEUED || it.status == AnalysisStatus.RUNNING
          }
      // A run the server lists may have changed while the app was closed. A night the list does not
      // reach is read by its own id, because the app no longer remembers the run it asked for. A
      // night in the list with no run has no analysis to settle, which is what automatic analysis
      // off leaves behind, and is not read again.
      val unsettled =
          record?.phase == UploadPhase.PROCESSING &&
              (night.analyses.isNotEmpty() || night.remote == null)
      val unfetched = night.isDone && !night.resultDownloaded
      if (working || unsettled || unfetched) {
        PendingRecording(night.key, recordingId, night.analysisId)
      } else {
        null
      }
    }
