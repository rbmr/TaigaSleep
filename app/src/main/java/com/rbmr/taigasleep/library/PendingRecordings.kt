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
 * - one of its runs is queued or running (or, for a run only the app remembers, was when the app
 *   last looked: it may have finished, failed or vanished while the app was closed);
 * - its stored state says processing and there is something to settle: a remembered run, or runs on
 *   the server. A night uploaded with automatic analysis off has neither and is left alone;
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
      val unsettled =
          record?.phase == UploadPhase.PROCESSING &&
              !(record.analysisId == null && night.analyses.isEmpty())
      val unfetched = night.isDone && !night.resultDownloaded
      if (working || unsettled || unfetched) {
        PendingRecording(night.key, recordingId, night.analysisId)
      } else {
        null
      }
    }
