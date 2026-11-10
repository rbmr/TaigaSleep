package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.recording.store.LogPosition
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.HrSample
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.TrackerEntry
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How far one import got: entry [entry] of [entries] is being stored, with [samples] tracker
 * samples on the phone for the entries read so far. An [entry] of 0 means no entry has started;
 * an [entries] of 0 means the tracker has not said what it holds yet, which is while it is still
 * being stopped and read.
 */
data class ImportProgress(val entry: Int, val entries: Int, val samples: Int)

/** What one attempt to move a tracker's recording onto the phone did. */
sealed interface OnDeviceFetch {
  /** The entries are stored and flushed; the caller may remove them from the tracker. */
  data class Imported(val entries: List<TrackerEntry>, val samples: Int) : OnDeviceFetch

  /** Nothing may be removed from the tracker; the same fetch can be tried again. */
  data class Failed(val reason: String) : OnDeviceFetch
}

/**
 * Stops the tracker, reads every stored entry, writes it as HR frames and flushes them. One
 * timeout covers all of it: when it expires the running work is cancelled before the caller can
 * remove anything, and the tracker keeps its copy. Nothing is removed here; the caller does that
 * only after [OnDeviceFetch.Imported].
 *
 * Samples of an entry are laid out from that entry's own start time, adding each RR interval in
 * order. An RR of 0 or less produces no frame, so the gap stays a gap. An entry is imported once:
 * the sample count already stored under its tracker id is read back and the rest are appended.
 */
class OnDeviceImport(
    private val writer: RecordingWriter,
    private val tracker: OnDeviceTracker,
    private val clock: Clock,
    private val timeoutMs: Long = FETCH_TIMEOUT_MS,
    private val onProgress: (ImportProgress) -> Unit = {},
) {
  private val cancelled = AtomicBoolean(false)

  fun fetch(): OnDeviceFetch {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "on-device-fetch") }
    val future = executor.submit(Callable { run() })
    return try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      cancel(future)
      OnDeviceFetch.Failed("timed out fetching the tracker recording")
    } catch (_: InterruptedException) {
      cancel(future)
      Thread.currentThread().interrupt()
      OnDeviceFetch.Failed("fetching the tracker recording was interrupted")
    } catch (e: ExecutionException) {
      val cause = e.cause
      OnDeviceFetch.Failed(
          cause?.message ?: cause?.javaClass?.simpleName ?: "fetching the tracker recording failed"
      )
    } finally {
      executor.shutdownNow()
      // Give the cancelled task a moment to stop before the caller can finish or remove anything.
      runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }
  }

  private fun cancel(future: Future<*>) {
    cancelled.set(true)
    future.cancel(true)
  }

  private fun run(): OnDeviceFetch {
    return try {
      // Nothing is known about the tracker's copy yet: it is still being stopped and read.
      report(ImportProgress(0, 0, 0))
      tracker.stopOnDevice()
      if (aborted()) return failed("fetch cancelled")
      repairUnaccountedSamples()?.let { return failed(it) }
      val entries = tracker.fetchOnDevice()
      if (aborted()) return failed("fetch cancelled")
      if (entries.isEmpty()) {
        // Nothing is left on the tracker. When the phone already holds imported entries, it has the
        // whole copy: a process that died before removing it leaves this, not a fetch that can
        // never finish.
        if (writer.importedOnDevice().isEmpty()) {
          return OnDeviceFetch.Failed("the tracker has no recording")
        }
        return OnDeviceFetch.Imported(emptyList(), 0)
      }
      var imported = 0
      var held = 0
      for ((index, entry) in entries.withIndex()) {
        if (aborted()) return failed("fetch cancelled")
        val number = index + 1
        report(ImportProgress(number, entries.size, held))
        val result = importEntry(entry) { written ->
          report(ImportProgress(number, entries.size, held + written))
        }
        if (result is EntryImport.Failed) return OnDeviceFetch.Failed(result.reason)
        imported += (result as EntryImport.Done).written
        held += entry.rrMs.count { it > 0 }
        report(ImportProgress(number, entries.size, held))
      }
      // The whole tracker copy must now be on the phone, not just the samples of this attempt.
      val fetchedSamples = entries.sumOf { entry -> entry.rrMs.count { it > 0 } }
      val stored = writer.importedOnDevice()
      val storedSamples = entries.sumOf { entry -> stored[entry.id] ?: 0 }
      if (storedSamples != fetchedSamples) {
        return OnDeviceFetch.Failed(
            "the imported sample count does not match the fetched one ($storedSamples/$fetchedSamples)"
        )
      }
      OnDeviceFetch.Imported(entries, imported)
    } catch (e: Exception) {
      OnDeviceFetch.Failed(e.message ?: e.javaClass.simpleName)
    }
  }

  private sealed interface EntryImport {
    data class Done(val written: Int) : EntryImport

    data class Failed(val reason: String) : EntryImport
  }

  /**
   * The HR log has to hold exactly the samples the recording counts as imported. More than that is
   * a half-written entry left by a process that died before it could take it back, which is cut
   * off before this fetch adds to the log; fewer means samples were lost, and the fetch refuses
   * rather than importing an entry that is partly stored already.
   */
  private fun repairUnaccountedSamples(): String? {
    val imported = writer.importedOnDevice().values.sumOf { it }.toLong()
    val frames = writer.hrFrames()
    if (frames == imported) return null
    if (frames < imported) {
      return "the recording holds $frames samples but $imported were imported"
    }
    if (!writer.truncateHr(imported)) {
      return "the recording holds $frames samples but only $imported were imported, " +
          "and the rest could not be removed"
    }
    return null
  }

  private fun importEntry(entry: TrackerEntry, onWritten: (Int) -> Unit): EntryImport {
    val already = writer.importedOnDevice()[entry.id] ?: 0
    val expected = entry.rrMs.count { it > 0 } - already
    // Persist where the logs stand, so a process kill mid-entry rolls it back and imports it once.
    if (!writer.beginEntryImport(entry.id)) {
      return EntryImport.Failed("marking the tracker entry before import failed")
    }
    val mark = writer.mark()
    var written = 0
    var seen = 0
    var timeMs = entry.startUtcMs
    for (rr in entry.rrMs) {
      if (rr <= 0) continue
      timeMs += rr
      seen++
      if (seen <= already) continue
      if (aborted()) return rollback(mark, written, "fetch cancelled")
      val sample =
          HrSample(
              60_000 / rr,
              contactSupported = false,
              contact = false,
              rrAvailable = true,
              rrMs = intArrayOf(rr),
          )
      val stamp = Stamp(timeMs * 1_000_000L, clock.elapsedNs())
      if (!writer.writeHr(stamp, sample)) {
        return rollback(mark, written, "writing the fetched samples failed")
      }
      written++
      // Long entries are reported as they are stored; a percentage of the whole would be invented,
      // but the samples actually written are not.
      if (written % PROGRESS_SAMPLES == 0) onWritten(written)
    }
    if (written != expected) {
      return rollback(mark, written, "the imported sample count does not match the fetched one")
    }
    if (!writer.sync()) {
      return rollback(mark, written, "flushing the fetched samples failed")
    }
    if (!writer.markOnDeviceImported(entry.id, seen)) {
      return rollback(mark, written, "recording the imported samples failed")
    }
    return EntryImport.Done(written)
  }

  private fun rollback(
      mark: Map<Int, LogPosition>,
      written: Int,
      reason: String,
  ): EntryImport {
    // Nothing was written, so there is nothing to take back; a rollback would only fail too.
    if (written == 0) return EntryImport.Failed(reason)
    val rolledBack = writer.rollback(mark)
    return EntryImport.Failed(
        if (rolledBack) reason else "$reason (and the half-written samples could not be removed)"
    )
  }

  private fun failed(reason: String) = OnDeviceFetch.Failed(reason)

  private fun report(progress: ImportProgress) {
    runCatching { onProgress(progress) }
  }

  private fun aborted(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted

  companion object {
    /** One timeout for stopping, reading, writing and flushing a tracker recording. */
    const val FETCH_TIMEOUT_MS = 60_000L

    /** How many samples may be written between two progress reports. */
    private const val PROGRESS_SAMPLES = 500
  }
}
