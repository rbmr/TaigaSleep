package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.library.parseApiDate
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.sync.UploadFailure

/** Where a running export is: it restores the raw files first when only the server has them. */
enum class ExportPhase {
  DOWNLOADING,
  ZIPPING,
}

/** The messages the recording screen shows. They come from resources, so the view model has no context. */
data class RecordingTexts(
    val loadFailed: String,
    val loginError: String,
    val rawRestoreFailed: String,
    val rawDeleteFailed: String,
    val rawNotUploaded: String,
)

/**
 * Everything the recording screen draws, for one recording. The recording itself and its analyses come from the
 * library; the rest is what the screen has asked for or is waiting on.
 */
data class RecordingDetailUiState(
    val recording: RecordingView? = null,
    val result: AnalysisDto? = null,
    val loading: Boolean = true,
    val offlineSaved: Boolean = false,
    val error: String? = null,
    val loginExpired: Boolean = false,
    val connected: Boolean = false,
    val actionError: String? = null,
    /** Bytes of the raw streams on this phone, read while the details sheet is open. */
    val rawSizeBytes: Long = 0,
    val showDetails: Boolean = false,
    val exportBusy: Boolean = false,
    val exportPhase: ExportPhase? = null,
    val exportProgress: Float? = null,
    val algorithms: List<AlgorithmDto> = emptyList(),
    val algorithmsLoading: Boolean = false,
    val algorithmsError: String? = null,
    val analysisBusy: Boolean = false,
    /** Something can produce an export: the phone has the raw files, or the server does. */
    val canExport: Boolean = false,
    val loginRequested: Boolean = false,
    val closeRequested: Boolean = false,
    val loginError: String = "",
    /** The account an upload needs the user's agreement for, because another one already has it. */
    val uploadConfirmation: UploadTarget? = null,
) {
  val status: RecordingStatus
    get() = recording?.status ?: RecordingStatus.LOCAL_ONLY

  /**
   * The library already knows this night has a finished analysis; a result that is not read yet
   * changes nothing.
   */
  val analysed: Boolean
    get() = recording?.isDone == true

  /** The failure the status line reports, with a rejected login taking over the stored one. */
  val failure: UploadFailure?
    get() = if (loginExpired) UploadFailure.LOGIN else recording?.failure

  val errorDetail: String?
    get() = if (loginExpired) loginError else recording?.errorDetail

  val local: Recording?
    get() = recording?.local

  val remoteId: String?
    get() = recording?.remoteId

  val selectedAnalysisId: String?
    get() = recording?.analysisId

  val analyses: List<Analysis>
    get() = recording?.analyses.orEmpty()

  val startUtcMs: Long
    get() = recording?.startUtcMs ?: 0

  val endUtcMs: Long?
    get() = recording?.endUtcMs

  val durationMs: Long
    get() = recording?.durationMs ?: 0

  val progress: Float
    get() = recording?.progress ?: 0f

  /** The device the screen is titled with: the phone's name wins over the server's. */
  val device: String
    get() = recording?.local?.sensorName ?: recording?.remote?.device.orEmpty()

  /** The tracker of the line under the title, from the recording on this phone. */
  val recordingTracker: String
    get() = local?.sensorName?.takeIf { it.isNotBlank() } ?: device

  /** The start of the line under the title, from the recording on this phone. */
  val recordingStartUtcMs: Long
    get() = local?.startUtcMs ?: startUtcMs

  /** The end of the line under the title, from the recording on this phone when it has one. */
  val recordingEndUtcMs: Long?
    get() = local?.endUtcMs ?: endUtcMs

  /** The device the details sheet lists, where a blank name falls through to the other copy. */
  val detailsDevice: String
    get() =
        recording?.local?.sensorName?.takeIf { it.isNotBlank() }
            ?: recording?.remote?.device?.takeIf { it.isNotBlank() }
            ?: ""

  /** When the server's copy was created, for the details sheet. */
  val remoteCreatedAtMs: Long?
    get() =
        parseApiDate(recording?.remote?.created.orEmpty()).takeIf { it > 0 }
            ?: recording?.backup?.uploadedAtMs

  /** The stage timeline of the shown result, which is what the charts draw. */
  val segments: List<StageSegmentDto>
    get() = result?.result?.stages?.segments ?: recording?.segments.orEmpty()

  val epochSeconds: Int
    get() = result?.result?.stages?.epoch_seconds ?: recording?.epochSeconds ?: 30

  /** The start of the drawn recording, from the recording or from the result's timeline. */
  val start: Long
    get() = startUtcMs.takeIf { it > 0 } ?: result?.result?.stages?.start_unix_ms ?: 0L

}
