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

/** The messages the night screen shows. They come from resources, so the view model has no context. */
data class NightTexts(
    val loadFailed: String,
    val loginError: String,
    val rawRestoreFailed: String,
    val rawDeleteFailed: String,
    val rawNotUploaded: String,
)

/**
 * Everything the night screen draws, for one night. The night itself and its analyses come from the
 * library; the rest is what the screen has asked for or is waiting on.
 */
data class RecordingDetailUiState(
    val night: RecordingView? = null,
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
) {
  val status: RecordingStatus
    get() = night?.status ?: RecordingStatus.LOCAL_ONLY

  /** The failure the status line reports, with a rejected login taking over the stored one. */
  val failure: UploadFailure?
    get() = if (loginExpired) UploadFailure.LOGIN else night?.failure

  val errorDetail: String?
    get() = if (loginExpired) loginError else night?.errorDetail

  val local: Recording?
    get() = night?.local

  val remoteId: String?
    get() = night?.remoteId

  val selectedAnalysisId: String?
    get() = night?.analysisId

  val analyses: List<Analysis>
    get() = night?.analyses.orEmpty()

  val startUtcMs: Long
    get() = night?.startUtcMs ?: 0

  val endUtcMs: Long?
    get() = night?.endUtcMs

  val durationMs: Long
    get() = night?.durationMs ?: 0

  val progress: Float
    get() = night?.progress ?: 0f

  /** The device the screen is titled with: the phone's name wins over the server's. */
  val device: String
    get() = night?.local?.sensorName ?: night?.remote?.device.orEmpty()

  /** The device the details sheet lists, where a blank name falls through to the other copy. */
  val detailsDevice: String
    get() =
        night?.local?.sensorName?.takeIf { it.isNotBlank() }
            ?: night?.remote?.device?.takeIf { it.isNotBlank() }
            ?: ""

  /** When the server's copy was created, for the details sheet. */
  val remoteCreatedAtMs: Long?
    get() =
        parseApiDate(night?.remote?.created.orEmpty()).takeIf { it > 0 }
            ?: night?.backup?.uploadedAtMs

  /** The stage timeline of the shown result, which is what the charts draw. */
  val segments: List<StageSegmentDto>
    get() = result?.result?.stages?.segments ?: night?.segments.orEmpty()

  val epochSeconds: Int
    get() = result?.result?.stages?.epoch_seconds ?: night?.epochSeconds ?: 30

  /** The start of the drawn night, from the recording or from the result's timeline. */
  val start: Long
    get() = startUtcMs.takeIf { it > 0 } ?: result?.result?.stages?.start_unix_ms ?: 0L

}
