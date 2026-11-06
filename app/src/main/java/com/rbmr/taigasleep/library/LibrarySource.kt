package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens may read from [RecordingLibrary]: the recordings as one list and the one connection
 * state. The library stays the only thing that writes either of them; a screen depends on this
 * port so its logic can be exercised without the stores.
 */
interface LibrarySource {
  val recordings: StateFlow<List<RecordingView>>

  val remoteStatus: StateFlow<RemoteStatus>

  /** Re-read the cached server list, for example after the account or the app resumed. */
  fun reload()
}

/** One recording, for the screen that shows it: its stored copy, its result and its pending runs. */
interface NightSource : LibrarySource {
  /** The recording with this key, or null when the list does not hold it (yet). */
  fun recording(key: String): RecordingView?

  /** The full cached payload for one analysis, if it is on this phone. */
  fun cachedResult(key: String, analysisId: String?): AnalysisDto?

  /** Refresh one recording and its analyses, once. */
  suspend fun refreshRecording(
      key: String,
      remoteId: String?,
      analysisId: String?,
  ): Result<AnalysisDto?>

  /** Installed algorithms the server reports that this recording can actually feed. */
  suspend fun compatibleAlgorithms(recording: RecordingView): Result<List<AlgorithmDto>>

  /** Request one explicit analysis for a recording. */
  suspend fun requestManual(recording: RecordingView, algorithmId: String): Result<Unit>

  /** Remember which finished run the user wants to see. */
  fun selectAnalysis(recording: RecordingView, analysisId: String)
}
