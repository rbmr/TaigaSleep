package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.RemoteStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens may read from [RecordingLibrary]: the nights as one list and the one connection
 * state. The library stays the only thing that writes either of them; a screen depends on this
 * port so its logic can be exercised without the stores.
 */
interface LibrarySource {
  val recordings: StateFlow<List<RecordingView>>

  val remoteStatus: StateFlow<RemoteStatus>

  /** Re-read the cached server list, for example after the account or the app resumed. */
  fun reload()
}
