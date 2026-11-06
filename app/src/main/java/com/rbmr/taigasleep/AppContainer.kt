package com.rbmr.taigasleep

import android.app.Application
import android.content.Context
import com.rbmr.taigasleep.backend.OkHttpRemoteApi
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.remoteStore
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.RecordingDirectoryStorage
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadStore
import java.io.File

/**
 * The app's long-lived collaborators, built once with their real dependencies and shared by every
 * caller. [TaigaApplication] owns the one instance; other packages reach it through
 * [Context.appContainer] and never construct these classes themselves.
 */
class AppContainer(private val application: Application) {
  val recordingStore: RecordingStore by lazy {
    val base = application.getExternalFilesDir(null) ?: application.filesDir
    RecordingStore(File(base, "recordings"))
  }

  val uploadStore: UploadStore by lazy {
    UploadStore(
        RecordingDirectoryStorage(
            recordingStore.root,
            application.getSharedPreferences("uploads", Context.MODE_PRIVATE),
        )
    )
  }

  val remoteRepository: RemoteRepository by lazy {
    RemoteRepository(store = remoteStore(application), api = OkHttpRemoteApi())
  }

  val pendingDeleteStore: PendingDeleteStore by lazy {
    PendingDeleteStore(application.getSharedPreferences("deletes", Context.MODE_PRIVATE))
  }

  val resultCache: ResultCache by lazy { ResultCache(File(application.filesDir, "analysis")) }

  val remoteHistoryCache: RemoteHistoryCache by lazy {
    // The list of an older app holds session summaries this version cannot read.
    File(application.filesDir, "history/remote-sessions.json").delete()
    RemoteHistoryCache(File(application.filesDir, "history/remote-recordings.json"))
  }
}

/** The process-wide container, reached from any Context through the application object. */
val Context.appContainer: AppContainer
  get() = (applicationContext as TaigaApplication).container
