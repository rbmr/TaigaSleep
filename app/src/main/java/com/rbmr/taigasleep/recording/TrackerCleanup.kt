package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import java.io.File

/** What one attempt to remove a recording's copy from the tracker did. */
sealed interface TrackerRemoval {
  /** The tracker holds none of the entries any more. */
  data object Done : TrackerRemoval

  /**
   * The tracker still holds them. [attempts] is how many connections in a row failed, so the caller
   * can wait before it tells the user.
   */
  data class Failed(val attempts: Int, val reason: String) : TrackerRemoval
}

/**
 * The tracker copies one recording still has to remove.
 *
 * The list lives in meta.json and is written before the recording is finished, so a process that
 * dies between finishing and removing finds the removal again instead of a fetch that can never
 * succeed. A removal that fails is retried the next time the tracker is connected; the recording is
 * already finished, so a failure costs nothing until it keeps failing.
 */
class TrackerCleanup(private val dir: File, private val tracker: OnDeviceTracker) {
  /** Retries every entry the recording still has to remove from the tracker. */
  fun retry(): TrackerRemoval {
    val meta =
        RecordingMeta.read(dir)
            ?: return TrackerRemoval.Failed(0, "the recording metadata is missing")
    val ids = meta.trackerRemovalPending
    if (ids.isEmpty()) return TrackerRemoval.Done
    return try {
      tracker.removeFromDevice(ids)
      // An entry the tracker no longer has is gone, which is everything the removal wanted.
      if (!meta.copy(trackerRemovalPending = emptyList(), trackerRemovalFailures = 0).write(dir)) {
        return TrackerRemoval.Failed(meta.trackerRemovalFailures, "the metadata could not be saved")
      }
      TrackerRemoval.Done
    } catch (e: Exception) {
      val attempts = meta.trackerRemovalFailures + 1
      meta.copy(trackerRemovalFailures = attempts).write(dir)
      TrackerRemoval.Failed(attempts, e.message ?: e.javaClass.simpleName)
    }
  }

  companion object {
    /** Consecutive failed connections after which the removal is worth telling the user about. */
    const val SHOW_AFTER = 3
  }
}
