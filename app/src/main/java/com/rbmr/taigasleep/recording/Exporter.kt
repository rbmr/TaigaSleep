package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.rbmr.taigasleep.appContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the bytes for an export come from. */
enum class ExportSource {
  /** Everything the export asks for is already on the phone. */
  LOCAL,
  /** The raw files and the metadata must be fetched back from the server first. */
  RESTORE,
  /** Neither copy can produce the export the user asked for. */
  UNAVAILABLE,
}

/**
 * What a night's export contains. The raw stream logs and the metadata (everything that is not a raw
 * stream log, such as `meta.json` and the event log) are selected by default because that is the
 * recording as it was made; analysis results are opt-in.
 */
data class ExportContents(
    val raw: Boolean = true,
    val metadata: Boolean = true,
    val results: Boolean = false,
) {
  val isEmpty: Boolean
    get() = !raw && !metadata && !results

  val resultsOnly: Boolean
    get() = results && !raw && !metadata
}

/** How far preparing an export is: bytes written of the bytes the chosen contents hold. */
data class ExportProgress(val doneBytes: Long, val totalBytes: Long) {
  val fraction: Float
    get() =
        if (totalBytes <= 0) 1f
        else (doneBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * An export at least this large reports a percentage. A shorter one finishes before a percentage
 * would mean anything, so it only says that work is happening.
 */
const val LARGE_EXPORT_BYTES = 8L * 1024 * 1024

/** One analysis payload the export writes, as the JSON the cache holds for it. */
data class ExportResult(val name: String, val json: String)

/** The file one analysis result has inside an export archive. */
fun analysisExportName(analysisId: String): String = "analysis-$analysisId.json"

/**
 * Where an export can come from. Only the selected contents count: a metadata-only or results-only
 * export never asks the server for raw files it does not need.
 */
fun exportSource(
    contents: ExportContents,
    hasRaw: Boolean,
    hasMetadata: Boolean,
    hasResults: Boolean,
    onServer: Boolean,
): ExportSource =
    when {
      contents.isEmpty -> ExportSource.UNAVAILABLE
      contents.resultsOnly && !hasResults -> ExportSource.UNAVAILABLE
      (contents.raw && !hasRaw) || (contents.metadata && !hasMetadata) ->
          if (onServer) ExportSource.RESTORE else ExportSource.UNAVAILABLE
      else -> ExportSource.LOCAL
    }

/** Builds a recording's chosen contents into one archive in the cache directory. */
object Exporter {
  /** Writes the archive to the cache directory and returns it. Nothing of it is kept afterwards. */
  suspend fun prepare(
      context: Context,
      recordingId: String,
      contents: ExportContents,
      results: List<ExportResult>,
      onProgress: (ExportProgress) -> Unit = {},
  ): File =
      withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, EXPORT_DIR)
        // One archive at a time: anything left from an earlier export is not needed.
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "taiga-$recordingId.zip").also { zip ->
          RecordingExport.write(
              id = recordingId,
              dir = context.appContainer.recordingStore.dir(recordingId),
              out = zip,
              contents = contents,
              results = results,
              onProgress = onProgress,
          )
        }
      }

  /** The share sheet for a prepared archive. */
  fun shareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, null)
  }

  /** The share sheet returned: an export is not kept on the phone once it has been handed over. */
  fun discard(file: File) {
    file.delete()
    // The directory holds the one archive of the export it was made for.
    file.parentFile?.delete()
  }

  private const val EXPORT_DIR = "export"
}
