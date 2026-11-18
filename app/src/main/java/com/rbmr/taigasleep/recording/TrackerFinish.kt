@file:Suppress("TooManyFunctions")

package com.rbmr.taigasleep.recording

import android.util.Log
import com.rbmr.taigasleep.recording.store.EventLog
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.SensorKind
import java.io.File
import java.io.IOException

/** What one attempt to take a recording the tracker stored onto the phone came to. */
internal sealed interface TrackerFetch {
  /** The night is on the phone and verified; the tracker's copy is marked for removal. */
  data object Imported : TrackerFetch

  /** Nothing was taken: the control flow finishes the recording with [reason] for the user. */
  data class Failed(val reason: String) : TrackerFetch
}

/**
 * Finishing a recording the tracker stored: fetching its night, importing it into the recording and
 * removing the tracker's copy. The stop of a live on-device recording and the retry of a failed one
 * run the same import sequence and report a removal result the same way; they differ only in what is
 * done with the outcome, because only the live one is still a transition of the session.
 *
 * [RecordingControl] decides when a stop or a retry happens; this performs it.
 */
internal class TrackerFinish(
    private val store: RecordingStore,
    private val clock: Clock,
    private val host: RecordingHost,
    /** The attached session, so a fetch can tell whether another path ended it meanwhile. */
    private val live: () -> LiveSession?,
    /** Drops the session of a recording that is finished already. */
    private val detach: () -> Unit,
    /** Nothing runs here: leave the service idle again, optionally saying why. */
    private val leave: (String?) -> Unit,
) {
  /**
   * Takes the night off the tracker for the session the user stopped, and marks the tracker's copy
   * for removal once every sample is verified. Null when another path owns the session: a forced
   * stop or a restart ended it while the fetch ran, and then the tracker keeps its copy.
   */
  fun fromTracker(s: LiveSession, finishing: FinishingState): TrackerFetch? {
    val tracker = s.sensor as? OnDeviceTracker
    if (tracker == null) {
      if (!s.claim()) return null
      return TrackerFetch.Failed("this tracker cannot hand over the recording")
    }
    // Write the pending state before starting, so a process kill mid-fetch is found after restart.
    if (!s.writer.markFetchPending()) {
      // A forced stop closed the recording while the fetch was about to start.
      if (live() !== s) return null
      return TrackerFetch.Failed("could not record that the fetch is still pending")
    }
    return when (val result = import(s.writer, tracker, finishing)) {
      is OnDeviceFetch.Imported -> {
        // A forced stop may have ended the recording while the fetch ran: then what it brought is
        // not taken and the tracker keeps its copy.
        if (live() !== s || !s.claim()) return null
        // The night is on the phone: finish the recording, then take the tracker's copy away. A
        // process that dies in between leaves a removal still to do, not a fetch that repeats.
        if (!s.writer.markTrackerRemoval(result.entries.map { it.id })) {
          s.writer.event("error", "the tracker copy could not be remembered for removal")
        }
        RecordingRepository.setFinishing(finishing.copy(step = FinishingState.Step.SAVING))
        TrackerFetch.Imported
      }
      is OnDeviceFetch.Failed -> {
        if (live() !== s || !s.claim()) return null
        TrackerFetch.Failed(result.reason)
      }
    }
  }

  /**
   * Retries the fetch of a recording the tracker still holds, without a live recording: the service
   * reopens the recording, imports whatever is missing and then removes the tracker's copy.
   */
  fun retry(recordingId: String?) {
    if (live() != null) return
    val id = recordingId ?: store.fetchPending()?.recordingId
    val dir = id?.let { store.dir(it) }
    val meta = dir?.let { RecordingMeta.read(it) }
    if (dir == null || meta == null || !meta.fetchPending) {
      leave(host.message(RecordingMessage.FETCH_MISSING))
      return
    }
    val kind = SensorKind.fromWire(meta.sensorKind)
    val tracker =
        if (kind == null) {
          null
        } else {
          runCatching { host.obtain(kind, meta.sensorId, meta.sensorName) }.getOrNull()
              as? OnDeviceTracker
        }
    if (tracker == null) {
      leavePending(meta, host.message(RecordingMessage.FETCH_FAILED))
      return
    }
    val writer = RecordingWriter.resumeFetch(dir, clock, "fetching from the tracker")
    if (writer == null) {
      // The recording is there but could not be reopened from a consistent state; the event log
      // says what the half-written entry was. It stays fetch pending for another attempt.
      leavePending(meta, host.message(RecordingMessage.FETCH_RECOVERY))
      return
    }
    val finishing =
        FinishingState(
            recordingId = meta.recordingId,
            sensorName = meta.sensorName,
            mode = meta.mode,
            step = FinishingState.Step.FETCHING,
        )
    RecordingRepository.setFinishing(finishing)
    when (val result = import(writer, tracker, finishing)) {
      is OnDeviceFetch.Imported -> {
        if (!writer.markTrackerRemoval(result.entries.map { it.id })) {
          writer.event("error", "the tracker copy could not be remembered for removal")
        }
        RecordingRepository.setFinishing(finishing.copy(step = FinishingState.Step.SAVING))
        writer.finish(RecordingPhase.STOPPED, "recording fetched from the tracker")
        remove(writer, tracker)
        RecordingRepository.setFinishing(finishing.copy(step = FinishingState.Step.DONE))
        host.onFetchCompleted(meta.recordingId)
      }
      is OnDeviceFetch.Failed -> {
        writer.event("error", "fetching the tracker recording failed: ${result.reason}")
        writer.finish(RecordingPhase.FETCH_PENDING, "fetch failed; the tracker keeps the recording")
        RecordingRepository.setFetchPending(
            PendingFetch(meta.recordingId, meta.sensorName, meta.startUtcMs, result.reason)
        )
      }
    }
    writer.close()
    finishFetchService()
  }

  /** One attempt to import everything the tracker holds, reported to [finishing] as it goes. */
  private fun import(
      writer: RecordingWriter,
      tracker: OnDeviceTracker,
      finishing: FinishingState,
  ): OnDeviceFetch =
      OnDeviceImport(writer, tracker, clock) { progress ->
            RecordingRepository.setFinishing(finishing.fetched(imported = progress))
          }
          .fetch()

  /** Keeps the stopping step until the tracker says what it holds; after that it is the fetch. */
  private fun FinishingState.fetched(imported: ImportProgress) =
      if (imported.entries == 0) {
        copy(step = FinishingState.Step.STOPPING_TRACKER, fetch = null)
      } else {
        copy(step = FinishingState.Step.FETCHING, fetch = imported)
      }

  /**
   * Removes the entries the recording still has to take off the tracker. The recording is finished
   * already, so a failure is remembered in its meta.json and retried when the tracker is next
   * connected; only a removal that keeps failing is worth telling the user about.
   */
  fun remove(writer: RecordingWriter, tracker: OnDeviceTracker) =
      report(TrackerCleanup(writer.dir, tracker).retry(), writer::event)

  /** A failed removal is silent until it has failed on [TrackerCleanup.SHOW_AFTER] connections. */
  private fun report(result: TrackerRemoval, event: (String, String) -> Unit) {
    if (result !is TrackerRemoval.Failed) return
    if (result.attempts == TrackerCleanup.SHOW_AFTER) {
      event("error", "the tracker copy could not be removed: ${result.reason}")
    }
  }

  /**
   * Retries the tracker copies that could not be removed when their recordings ended. The app opens
   * by connecting the tracker again, which is the moment those removals get their next chance;
   * nothing else asks for them, because their recordings are finished already.
   */
  fun retryRemovals() {
    if (live() != null) return
    for (meta in store.pendingTrackerRemovals()) {
      if (live() != null) break
      val kind = SensorKind.fromWire(meta.sensorKind) ?: continue
      val tracker =
          runCatching { host.obtain(kind, meta.sensorId, meta.sensorName) }.getOrNull()
              as? OnDeviceTracker ?: continue
      val dir = store.dir(meta.recordingId)
      report(TrackerCleanup(dir, tracker).retry()) { type, message ->
        recordEvent(dir, type, message)
      }
    }
    finishCleanup()
  }

  /** An event for a recording no writer is open for: it ended before the removal was retried. */
  private fun recordEvent(dir: File, type: String, message: String) {
    try {
      EventLog.append(dir, clock.phoneNs(), clock.elapsedNs(), type, message)
    } catch (e: IOException) {
      Log.w(TAG, "event write failed", e)
    }
  }

  /** The cleanup action is done; it changes nothing the screen shows. */
  private fun finishCleanup() {
    host.finishService()
  }

  /** The retry could not even start: keep it on the screen, with the reason. */
  private fun leavePending(meta: RecordingMeta, error: String) {
    RecordingRepository.setFetchPending(
        PendingFetch(meta.recordingId, meta.sensorName, meta.startUtcMs, error)
    )
    finishFetchService()
  }

  private fun finishFetchService() {
    detach()
    host.markRunning(false)
    host.activeSessionId = null
    host.cancelWatchdog()
    host.refreshRecordings()
    host.finishService()
  }

  private companion object {
    const val TAG = "TrackerFinish"
  }
}
