package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.rbmr.taigasleep.storage.SessionStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Zips a session into the cache directory on demand and offers it to the share sheet. */
object Exporter {
  suspend fun share(context: Context, sessionId: String) {
    val zip =
        withContext(Dispatchers.IO) {
          val dir = File(context.cacheDir, "export")
          dir.deleteRecursively()
          dir.mkdirs()
          File(dir, "taiga-$sessionId.zip").also { SessionStore.of(context).zip(sessionId, it) }
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
