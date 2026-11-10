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

/**
 * Builds a recording's chosen contents into one archive in the cache directory and offers it to the
 * share sheet. The archive stays there until the next export or the next app start: a share target
 * can still read it after the chooser has closed.
 */
object Exporter {
  /** An archive from the last hour may still be read by the app it was shared with. */
  const val STALE_EXPORT_MS = 60L * 60 * 1000

  /** Every prepared archive lives in this cache directory, one at a time. */
  private const val EXPORT_DIR = "export"

  /** Writes the archive and starts the share sheet for it. */
  suspend fun share(
      context: Context,
      recordingId: String,
      contents: ExportContents,
      results: List<ExportResult>,
      onProgress: (ExportProgress) -> Unit = {},
  ) {
    val archive =
        withContext(Dispatchers.IO) { prepare(context, recordingId, contents, results, onProgress) }
    val send = shareIntent(context, archive).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(send)
  }

  /** Writes the archive to the cache directory, removing every export an earlier one left. */
  suspend fun prepare(
      context: Context,
      recordingId: String,
      contents: ExportContents,
      results: List<ExportResult>,
      onProgress: (ExportProgress) -> Unit = {},
  ): File =
      withContext(Dispatchers.IO) {
        val dir = exportDir(context)
        clear(dir)
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

  /** Removes the exports a newer one replaces. */
  fun clear(dir: File) {
    dir.deleteRecursively()
    dir.mkdirs()
  }

  /**
   * Removes the exports an earlier app run left behind. One that is still recent may be shared
   * right now, so it stays until it is [maxAgeMs] old.
   */
  fun removeStale(
      dir: File,
      nowMs: Long = System.currentTimeMillis(),
      maxAgeMs: Long = STALE_EXPORT_MS,
  ) {
    dir.listFiles()?.forEach { file ->
      if (file.lastModified() < nowMs - maxAgeMs) file.deleteRecursively()
    }
  }

  /** Called when the app starts, off the main thread: no export outlives the share it was made for. */
  suspend fun cleanupOnStart(context: Context) =
      withContext(Dispatchers.IO) { removeStale(exportDir(context)) }

  private fun exportDir(context: Context): File = File(context.cacheDir, EXPORT_DIR)

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
}
