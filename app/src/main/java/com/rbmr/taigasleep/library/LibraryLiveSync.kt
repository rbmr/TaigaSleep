package com.rbmr.taigasleep.library

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.sync.LiveSync

/** The backend's live event stream wired to refresh [RecordingLibrary]; one per process. */
object LibraryLiveSync {
  @Volatile private var instance: LiveSync? = null

  fun of(context: Context): LiveSync =
      instance
          ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
          }

  private fun build(context: Context): LiveSync {
    val remote = context.appContainer.remoteRepository
    return LiveSync(
        gate = remote.streamAccount,
        events = remote.events(),
        target = RecordingLibrary.of(context),
    )
  }
}
