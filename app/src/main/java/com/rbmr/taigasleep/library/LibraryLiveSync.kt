package com.rbmr.taigasleep.library

import android.content.Context
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.sync.LiveSync

/** The backend's live event stream wired to refresh [NightRepository]; one per process. */
object LibraryLiveSync {
  @Volatile private var instance: LiveSync? = null

  fun of(context: Context): LiveSync =
      instance
          ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
          }

  private fun build(context: Context): LiveSync {
    val remote = RemoteRepository.of(context)
    return LiveSync(
        gate = remote.streamAccount,
        events = remote.events(),
        target = NightRepository.of(context),
    )
  }
}
