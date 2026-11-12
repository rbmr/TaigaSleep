package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.library.parseApiDate
import com.rbmr.taigasleep.recording.ExportContents
import com.rbmr.taigasleep.recording.ExportSource
import com.rbmr.taigasleep.recording.exportSource
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
    /** Puts the reason a call failed after the message that says what failed: `%1$s: %2$s`. */
    val errorDetail: String,
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
    /** Bytes of the raw streams on this phone. */
    val rawSizeBytes: Long = 0,
    /** The streams the recording holds on this phone, as the ids of the store's stream files. */
    val rawStreams: List<Int> = emptyList(),
    val exportBusy: Boolean = false,
    val exportPhase: ExportPhase? = null,
    /** 0..1 while a large export is written, null while it is short or has not measured itself. */
    val exportProgress: Float? = null,
    val exportDoneBytes: Long = 0,
    val exportTotalBytes: Long = 0,
    /** What the user chose to export; the raw files and the metadata unless changed. */
    val exportContents: ExportContents = ExportContents(),
    val algorithms: List<AlgorithmDto> = emptyList(),
    val algorithmsLoading: Boolean = false,
    val algorithmsError: String? = null,
    val analysisBusy: Boolean = false,
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

  /** A payload of this recording that is already on the phone; an export never fetches one. */
  val resultsExportable: Boolean
    get() = result != null || analyses.any { it.resultDownloaded }

  /** The raw streams of the export selection. */
  val exportRaw: Boolean
    get() = exportContents.raw

  /** The metadata of the export selection. */
  val exportMetadata: Boolean
    get() = exportContents.metadata

  /** The analysis results of the export selection. */
  val exportResults: Boolean
    get() = exportContents.results

  /** The raw stream logs can be exported: they are here, or the server can give them back. */
  val rawExportable: Boolean
    get() = local?.hasRaw == true || remoteId != null

  /** The metadata is here, or comes back with the raw files from the server. */
  val metadataExportable: Boolean
    get() = local != null || remoteId != null

  /** Where the chosen contents can come from: the phone, the server, or neither. */
  private val exportAvailability: ExportSource
    get() =
        exportSource(
            contents = exportContents,
            hasRaw = local?.hasRaw == true,
            hasMetadata = local != null,
            hasResults = resultsExportable,
            onServer = remoteId != null,
        )

  /** The selected contents can be produced; a night with no local copy is exported after a restore. */
  val canExport: Boolean
    get() = local != null && exportAvailability != ExportSource.UNAVAILABLE

  /** The selected contents need the raw files back from the server before they can be written. */
  val exportNeedsRestore: Boolean
    get() = exportAvailability == ExportSource.RESTORE

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

  /** The device the recording section lists, where a blank name falls through to the other copy. */
  val detailsDevice: String
    get() =
        recording?.local?.sensorName?.takeIf { it.isNotBlank() }
            ?: recording?.remote?.device?.takeIf { it.isNotBlank() }
            ?: ""

  /** When the server's copy was made, which the recording section shows beside its pill. */
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

/**
 * Whether the screen draws the score card. A night whose analysis finished draws it, and so does a
 * night whose analysis the server has queued or running, because a result is coming. A night that is
 * only on the phone, still uploading, or whose upload or analysis failed has no result coming: its
 * status card says what it is, and a card promising a result above it would contradict it.
 */
internal fun showsScoreCard(status: RecordingStatus, hasResult: Boolean): Boolean =
    hasResult ||
        status == RecordingStatus.READY ||
        status == RecordingStatus.PROCESSING
