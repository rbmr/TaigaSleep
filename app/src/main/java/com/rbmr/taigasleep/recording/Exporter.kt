package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the bytes for an export come from. */
enum class ExportSource {
  /** The raw files are already on the phone. */
  LOCAL,
  /** The metadata and raw files must be fetched back from the server first. */
  RESTORE,
  /** Neither copy can produce an export. */
  UNAVAILABLE,
}

fun exportSource(hasRaw: Boolean, onServer: Boolean): ExportSource =
    when {
      hasRaw -> ExportSource.LOCAL
      onServer -> ExportSource.RESTORE
      else -> ExportSource.UNAVAILABLE
    }

/** Zips a session into the cache directory on demand and offers it to the share sheet. */
object Exporter {
  suspend fun share(
      context: Context,
      recordingId: String,
      onProgress: (Float) -> Unit = {},
  ) {
    val zip =
        withContext(Dispatchers.IO) {
          val dir = File(context.cacheDir, "export")
          dir.deleteRecursively()
          dir.mkdirs()
          File(dir, "taiga-$recordingId.zip").also {
            context.appContainer.recordingStore.zip(recordingId, it, onProgress)
          }
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
}
