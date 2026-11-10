package com.rbmr.taigasleep.recording

import android.util.Log
import com.rbmr.taigasleep.diagnostics.BluetoothPower
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.EventLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Everything one start action carries, taken from the intent the service received. */
data class StartRequest(
    val kind: SensorKind?,
    val sensorId: String?,
    val sensorName: String?,
    val ecg: Boolean,
    val acc: Boolean,
    val mode: RecordingMode?,
)

/**
 * The control flow of one recording: what the service does, with no Android types left in it.
 *
 * Every lifecycle action runs on one control thread, because the steps of a recording have an
 * order. That is also its weakness: a tracker call that never returns blocks the one thread every
 * other action is queued behind. The watchdog's restart therefore runs on a thread of its own: it
 * drops the live session, closes the writer and the stalled executor, and opens the same recording
 * again on a new control thread. A refused foreground start is handled the same way, because it
 * has to clean up a session that may itself be stuck.
 *
 * The service performs [RecordingHost]'s Android work; everything else — the lifecycle
 * transitions, the recovery steps, the fetch retries — is here and is tested on the JVM.
 */
class RecordingControl(
    private val store: RecordingStore,
    private val clock: Clock,
    private val host: RecordingHost,
) {
  /** The thread a restart runs on: it must not be the control thread it is recovering. */
  private val recovery = Executors.newSingleThreadExecutor { Thread(it, "recording-recovery") }

  @Volatile private var control: ExecutorService = newControlThread()
  @Volatile private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + control.asCoroutineDispatcher())
  @Volatile private var live: Live? = null

  /** True while a live session is attached; the service logs it on the way out. */
  val hasLiveSession: Boolean
    get() = live != null

  private class Live(
      val writer: RecordingWriter,
      val sensor: SleepSensor,
      val mode: RecordingMode,
      val lifecycle: RecordingLifecycle,
      val fanout: SampleFanout,
      val wakeLock: RecordingWakeLock?,
      val monitor: RecordingMonitor,
  ) {
    var jobs = emptyList<Job>()

    /**
     * Set when the session is dropped. A control thread that was blocked inside a tracker call keeps
     * running once that call returns, and must not attach the dropped session again.
     */
    @Volatile var dropped = false

    /** Elapsed-realtime ms when the wake lock was last acquired, so it can be renewed in time. */
    var wakeLockSinceMs = 0L
  }

  // Actions. Each is queued on the control thread, except the ones that must run when it cannot.

  fun start(request: StartRequest) = post { startRecording(request) }

  fun resume() = post { resumeRecording() }

  fun stop(forced: Boolean) = post { stopRecording(forced) }

  fun discard() = post { discardRecording() }

  fun fetchPending(recordingId: String?) = post { fetchRecording(recordingId) }

  fun retryTrackerRemovals() = post { retryRemovals() }

  /**
   * The watchdog found the service alive but its heartbeat stopped. Its control thread is the one
   * that stopped, so the restart is not queued there: the session is closed, the old thread and
   * scope are dropped and the recording is resumed on a new one.
   */
  fun restart() {
    recovery.execute {
      runBlocking {
        try {
          live?.let { session ->
            Diagnostics.log("service_restart", mapOf("recording" to session.writer.meta.recordingId))
            teardown(session)
          }
          replaceControlThread()
          post { resumeRecording() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "the recording could not be restarted", e)
        }
      }
    }
  }

  /**
   * The system did not let the service run in the foreground, so the recording cannot be kept alive.
   * Like a restart, this must run even when the control thread is blocked: it is the only chance to
   * close the recording on disk and to keep an on-device night recoverable.
   */
  fun foregroundRefused() {
    recovery.execute {
      runBlocking {
        try {
          foregroundRefusedInternal()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "the refused recording could not be closed", e)
        }
      }
    }
  }

  /** The service is going away: stop the session and both threads without finishing the recording. */
  fun shutdown() {
    live?.let {
      // Leave meta.json at `recording` so the watchdog or the next app start can resume or mark it.
      it.dropped = true
      it.writer.sync()
      it.writer.close()
      releaseWakeLock(it)
    }
    live = null
    scope.cancel()
    control.shutdownNow()
    recovery.shutdownNow()
  }

  /** Running work goes on the control thread; after [shutdown] there is nowhere left to put it. */
  private fun post(action: suspend CoroutineScope.() -> Unit) {
    scope.launch { action() }
  }

  private fun newControlThread(): ExecutorService =
      Executors.newSingleThreadExecutor { Thread(it, "recording-control") }

  /**
   * Drops the thread that stalled and the scope bound to it, and makes a new pair for the same
   * recording. The old thread is interrupted; a tracker call that ignores that stays behind on a
   * cancelled scope, where it can no longer touch the new session.
   */
  private fun replaceControlThread() {
    val old = control
    scope.cancel()
    old.shutdownNow()
    control = newControlThread()
    scope = CoroutineScope(SupervisorJob() + control.asCoroutineDispatcher())
  }

  // Lifecycle. Each runs on the control thread.

  private suspend fun startRecording(request: StartRequest) {
    if (live != null) return
    val kind = request.kind
    val sensorId = request.sensorId
    if (kind == null || sensorId == null) {
      leave(host.message(RecordingMessage.NO_SENSOR))
      return
    }
    val sensor =
        try {
          host.obtain(kind, sensorId, request.sensorName ?: sensorId)
        } catch (e: IllegalStateException) {
          leave(e.message ?: host.message(RecordingMessage.NO_SENSOR))
          return
        }
    val config =
        RecordingConfig(
            ecg = request.ecg && sensor.capabilities.ecg,
            acc = request.acc && sensor.capabilities.acc,
        )
    val modes = sensor.recordingModes(StreamConfig(config.ecg, config.acc))
    if (modes.isEmpty()) {
      leave(host.message(RecordingMessage.MODE))
      return
    }
    val requested = request.mode
    val mode =
        when {
          requested != null && requested in modes -> requested
          RecordingMode.STREAM in modes -> RecordingMode.STREAM
          else -> modes.first()
        }
    // The same answer Home shows, from the flow the user picked: the app wiring supplies it. An
    // on-device recording skips the battery steps because the phone may sleep.
    if (!host.mayStart(mode)) {
      Log.w(TAG, "start refused: ${host.readinessSummary()}")
      leave(host.message(RecordingMessage.NOT_READY))
      return
    }
    // A night that cannot fit would fill the phone and lose the rest of it anyway.
    if (!StorageSpace.canStart(PhoneSpace(store.root).availableBytes(), config)) {
      Log.w(TAG, "start refused: not enough free space")
      leave(host.message(RecordingMessage.SPACE))
      return
    }
    val now = clock.phoneNs() / 1_000_000L
    val device = host.device
    val meta =
        RecordingMeta(
            recordingId = store.newRecordingId(now),
            state = RecordingPhase.RECORDING,
            mode = mode,
            startUtcMs = now,
            endUtcMs = null,
            timezone = TimeZone.getDefault().id,
            appVersion = device.appVersion,
            appVersionCode = device.appVersionCode,
            phoneManufacturer = device.manufacturer,
            phoneModel = device.model,
            android = device.android,
            sdk = device.sdk,
            sensorKind = sensor.kind.wire,
            sensorId = sensor.id,
            sensorName = sensor.name,
            sensorFirmware = sensor.firmware.value,
            sensorBatteryAtStart = sensor.battery.value,
            config = config,
        )
    val writer =
        try {
          RecordingWriter.create(store.root, meta, clock)
        } catch (e: Exception) {
          Log.e(TAG, "cannot create recording", e)
          leave(host.message(RecordingMessage.STORAGE))
          return
        }
    writer.event("readiness", host.readinessSummary())
    attach(writer, sensor, mode)
  }

  /** Picks up the recording that was running before this process or service instance died. */
  private suspend fun resumeRecording(contact: Boolean = true) {
    if (live != null) return
    val id = host.activeSessionId
    val dir = id?.let { store.dir(it) }
    val meta = dir?.let { RecordingMeta.read(it) }
    val kind = SensorKind.fromWire(meta?.sensorKind)
    if (dir == null || meta == null || kind == null || meta.state == RecordingPhase.STOPPED ||
        meta.state == RecordingPhase.FORCED_STOP) {
      host.activeSessionId = null
      host.cancelWatchdog()
      leave(null)
      return
    }
    val writer = RecordingWriter.resume(dir, clock, "service resumed recording") ?: run {
      leave(null)
      return
    }
    writer.event("readiness", host.readinessSummary())
    val sensor =
        try {
          host.obtain(kind, meta.sensorId, meta.sensorName)
        } catch (e: IllegalStateException) {
          writer.event("error", e.message ?: "sensor unavailable")
          writer.finish(RecordingPhase.INTERRUPTED, "cannot resume")
          host.activeSessionId = null
          leave(null)
          return
        }
    // A recording the tracker stores itself is already running there: it is adopted, never started
    // a second time, which is what a resume during an on-device start would otherwise do.
    attach(writer, sensor, meta.mode, parked = RecordingRecovery.resumesParked(meta.mode), contact)
  }

  /**
   * [parked] is an on-device recording that is already running on the tracker and is only being
   * picked up to be stopped or discarded: the tracker is not asked to start again. Without
   * [contact] the tracker is left alone altogether (force stop).
   */
  private fun attach(
      writer: RecordingWriter,
      sensor: SleepSensor,
      mode: RecordingMode,
      parked: Boolean = false,
      contact: Boolean = true,
  ) {
    val lifecycle = RecordingLifecycle(mode)
    val needsWakeLock = lifecycle.start().contains(LifecycleEffect.AcquireWakeLock)
    val acquiredAtMs = clock.elapsedNs() / NS_PER_MS
    val wakeLock =
        if (needsWakeLock) {
          host.newWakeLock()?.apply { acquire(RecordingRecovery.WAKE_LOCK_MS) }
        } else {
          null
        }
    val monitor =
        RecordingMonitor(
            meta = writer.meta,
            clock = clock,
            event = writer::event,
            space = PhoneSpace(writer.dir),
            storageHealth = writer::storageHealth,
        )
    monitor.onPhase(RecordingState.Phase.RECORDING)
    monitor.onLink(sensor.connection.value)
    val fanout =
        SampleFanout(writer, monitor) { message -> writer.event("error", message) }
    val s = Live(writer, sensor, mode, lifecycle, fanout, wakeLock, monitor)
    s.wakeLockSinceMs = acquiredAtMs
    live = s
    host.markRunning(true)
    host.activeSessionId = writer.meta.recordingId
    host.heartbeatMs = clock.phoneNs() / NS_PER_MS
    // An on-device recording needs no watchdog: nothing on the phone has to keep running.
    if (mode == RecordingMode.STREAM) host.scheduleWatchdog()
    Diagnostics.markRunning()
    Diagnostics.snapshot("recording_start")

    val cfg = writer.meta.config
    RecordingRepository.update {
      CaptureStatus(
          phase = Phase.RECORDING,
          recordingId = writer.meta.recordingId,
          startUtcMs = writer.meta.startUtcMs,
          config = cfg,
          sensorName = sensor.name,
          liveStreaming = mode == RecordingMode.STREAM && sensor.capabilities.live,
          state = monitor.state.value,
      )
    }
    val streamConfig = StreamConfig(cfg.ecg, cfg.acc)
    if (mode == RecordingMode.ON_DEVICE) {
      val tracker = sensor as? OnDeviceTracker
      if (tracker == null) {
        writer.finish(RecordingPhase.INTERRUPTED, "tracker cannot record on the device")
        end(s)
        return
      }
      if (RecordingRecovery.startsOnTracker(mode, parked)) {
        tracker.startOnDevice(streamConfig, fanout) { error -> post { onDeviceStarted(error) } }
      }
    } else {
      sensor.startStreams(streamConfig, fanout)
    }
    // The tracker call above can block for good. If the session was dropped in the meantime, the
    // recording is somebody else's now and this one stops here instead of attaching over it.
    if (s.dropped) return
    if (contact && sensor.connection.value == ConnectionState.DISCONNECTED) sensor.connect()

    s.jobs =
        buildList {
          // One collector feeds both consumers, so the screen and the notification never disagree.
          add(
              scope.launch {
                monitor.state.collect { state ->
                  if (live !== s) return@collect
                  RecordingRepository.update { it.copy(state = state) }
                  host.onState(state)
                }
              }
          )
          add(scope.launch { watchConnection(s) })
          add(scope.launch { tickLoop(s) })
          // A streamed recording is the only one whose link can be lost with the radio.
          if (mode == RecordingMode.STREAM) add(scope.launch { watchBluetooth(s) })
        }
    host.refreshRecordings()
  }

  /** The tracker is storing: the service is not needed until the tracker has to be stopped. */
  private suspend fun onDeviceStarted(error: String?) {
    val s = live ?: return
    s.jobs.forEach { it.cancel() }
    if (error != null) {
      s.writer.finish(RecordingPhase.INTERRUPTED, "tracker did not start")
      end(s)
      RecordingRepository.setIdle(error)
      return
    }
    s.writer.sync()
    s.writer.close()
    live = null
    host.markRunning(false)
    host.cancelWatchdog()
    releaseWakeLock(s)
    host.finishService()
  }

  /** Picks up the on-device recording that is running on the tracker, after the service was gone. */
  private suspend fun adopt(contact: Boolean): Live? {
    if (live == null) resumeRecording(contact = contact)
    return live
  }

  private suspend fun stopRecording(forced: Boolean) {
    val s = adopt(contact = !forced)
    if (s == null) {
      leave(null)
      return
    }
    RecordingRepository.update { it.copy(phase = Phase.STOPPING) }
    s.monitor.onPhase(RecordingState.Phase.STOPPING)
    s.jobs.forEach { it.cancel() }
    if (!forced && s.mode == RecordingMode.ON_DEVICE) {
      // Stop, fetch, persist, verify; only a verified import removes the tracker's copy.
      s.lifecycle.stop()
      fetchFromTracker(s)
    } else {
      apply(s, if (forced) s.lifecycle.forceStop() else s.lifecycle.stop())
      end(s)
    }
  }

  /** The on-device part of a stop: one timeout, then either a finished night or a retry. */
  private suspend fun fetchFromTracker(s: Live) {
    val tracker = s.sensor as? OnDeviceTracker
    if (tracker == null) {
      failFetch(s, "this tracker cannot hand over the recording")
      return
    }
    // Write the pending state before starting, so a process kill mid-fetch is found after restart.
    if (!s.writer.markFetchPending()) {
      failFetch(s, "could not record that the fetch is still pending")
      return
    }
    when (val result = OnDeviceImport(s.writer, tracker, clock).fetch()) {
      is OnDeviceFetch.Imported -> {
        // The night is on the phone: finish the recording, then take the tracker's copy away. A
        // process that dies in between leaves a removal still to do, not a fetch that repeats.
        if (!s.writer.markTrackerRemoval(result.entries.map { it.id })) {
          s.writer.event("error", "the tracker copy could not be remembered for removal")
        }
        apply(s, s.lifecycle.fetchSucceeded())
        end(s)
      }
      is OnDeviceFetch.Failed -> failFetch(s, result.reason)
    }
  }

  /** A failed fetch keeps the tracker's copy and leaves the recording ready for another attempt. */
  private suspend fun failFetch(s: Live, reason: String) {
    s.writer.event("error", "fetching the tracker recording failed: $reason")
    apply(s, s.lifecycle.fetchFailed())
    end(
        s,
        PendingFetch(
            recordingId = s.writer.meta.recordingId,
            sensorName = s.writer.meta.sensorName,
            startUtcMs = s.writer.meta.startUtcMs,
            error = reason,
        ),
    )
  }

  /**
   * Retries the fetch of a recording the tracker still holds, without a live recording: the service
   * reopens the recording, imports whatever is missing and then removes the tracker's copy.
   */
  private suspend fun fetchRecording(recordingId: String?) {
    if (live != null) return
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
    when (val result = OnDeviceImport(writer, tracker, clock).fetch()) {
      is OnDeviceFetch.Imported -> {
        if (!writer.markTrackerRemoval(result.entries.map { it.id })) {
          writer.event("error", "the tracker copy could not be remembered for removal")
        }
        writer.finish(RecordingPhase.STOPPED, "recording fetched from the tracker")
        report(TrackerCleanup(dir, tracker).retry(), writer::event)
        RecordingRepository.setIdle()
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

  /**
   * Removes the entries the recording still has to take off the tracker. The recording is finished
   * already, so a failure is remembered in its meta.json and retried when the tracker is next
   * connected; only a removal that keeps failing is worth telling the user about.
   */
  private fun removeFromTracker(writer: RecordingWriter, tracker: OnDeviceTracker) =
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
  private fun retryRemovals() {
    if (live != null) return
    for (meta in store.pendingTrackerRemovals()) {
      if (live != null) break
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
    live = null
    host.markRunning(false)
    host.activeSessionId = null
    host.cancelWatchdog()
    host.refreshRecordings()
    host.finishService()
  }

  private suspend fun discardRecording() {
    val s = adopt(contact = true)
    if (s == null) {
      leave(null)
      return
    }
    s.jobs.forEach { it.cancel() }
    apply(s, s.lifecycle.discard())
    end(s)
  }

  /** Performs the effects a lifecycle transition asked for, in order. */
  private suspend fun apply(s: Live, effects: List<LifecycleEffect>) {
    for (effect in effects) {
      when (effect) {
        LifecycleEffect.AcquireWakeLock -> Unit // Acquired in attach, before the first samples.
        LifecycleEffect.StopStreams ->
            withTimeoutOrNull(STOP_STREAMS_TIMEOUT_MS) {
              withContext(Dispatchers.IO) { runCatching { s.sensor.stopStreams() } }
            }
        LifecycleEffect.StopStreamsAsync ->
            Thread { runCatching { s.sensor.stopStreams() } }.start()
        // On-device stop runs the fetch itself, because its outcome decides the next effects.
        LifecycleEffect.FetchFromTracker -> Unit
        LifecycleEffect.RemoveFromTracker ->
            (s.sensor as? OnDeviceTracker)?.let { removeFromTracker(s.writer, it) }
        LifecycleEffect.DiscardOnTracker ->
            (s.sensor as? OnDeviceTracker)?.discardOnDevice()
        is LifecycleEffect.Finish -> {
          val message =
              when (effect.phase) {
                RecordingPhase.FORCED_STOP -> "forced stop"
                RecordingPhase.INTERRUPTED -> "interrupted"
                else -> "stopped by user"
              }
          s.writer.finish(effect.phase, message)
        }
        LifecycleEffect.DeleteLocal -> {
          s.writer.close()
          store.delete(s.writer.meta.recordingId)
        }
      }
    }
  }

  private fun end(s: Live, pending: PendingFetch? = null) {
    Diagnostics.log("service_stop", mapOf("state" to s.writer.meta.state.name.lowercase()))
    Diagnostics.markCleanShutdown("recording_ended")
    s.jobs.forEach { it.cancel() }
    live = null
    host.markRunning(false)
    host.activeSessionId = null
    host.cancelWatchdog()
    releaseWakeLock(s)
    if (pending != null) RecordingRepository.setFetchPending(pending) else RecordingRepository.setIdle()
    host.refreshRecordings()
    if (pending == null &&
        s.lifecycle.state != LifecycleState.DISCARDED &&
        s.writer.meta.state != RecordingPhase.RECORDING) {
      host.onRecordingFinished(s.writer.meta.recordingId)
    }
    host.finishService()
  }

  /** Nothing to record: drop the foreground state again, optionally reporting why. */
  private fun leave(error: String?) {
    RecordingRepository.setIdle(error)
    host.finishService()
  }

  private fun releaseWakeLock(s: Live) {
    s.wakeLock?.takeIf { it.isHeld }?.release()
  }

  /**
   * Drops the live session without ending the recording: its streams stop and its logs are closed,
   * so the directory can be opened again. meta.json stays as it is, so the recording stays open.
   */
  private suspend fun teardown(s: Live) {
    s.dropped = true
    s.jobs.forEach { it.cancel() }
    apply(s, s.lifecycle.restart())
    s.writer.sync()
    s.writer.close()
    releaseWakeLock(s)
    live = null
    host.markRunning(false)
  }

  /**
   * The system did not let this service run in the foreground, for example because the Bluetooth
   * permission was revoked or the start came from the background. The recording cannot be kept
   * alive, so it is closed with the reason on disk and on the screen, and nothing crashes. An
   * on-device recording is not ended: the tracker still holds the night, so it waits for the fetch
   * retry instead of losing it.
   */
  private suspend fun foregroundRefusedInternal() {
    live?.let { teardown(it) }
    val abandoned =
        RecordingRecovery.abandon(store, host.activeSessionId, REASON_NO_FOREGROUND, clock)
    Diagnostics.log(
        "service_foreground_refused",
        mapOf(
            "recording" to
                when (abandoned) {
                  is Abandoned.Interrupted -> abandoned.recordingId
                  is Abandoned.FetchPending -> abandoned.meta.recordingId
                  null -> null
                }
        ),
    )
    if (abandoned != null) {
      host.activeSessionId = null
      host.cancelWatchdog()
      host.refreshRecordings()
    }
    when (abandoned) {
      is Abandoned.Interrupted -> {
        host.onRecordingFinished(abandoned.recordingId)
        RecordingRepository.setIdle(host.message(RecordingMessage.FOREGROUND))
      }
      is Abandoned.FetchPending -> {
        val meta = abandoned.meta
        RecordingRepository.setFetchPending(
            PendingFetch(meta.recordingId, meta.sensorName, meta.startUtcMs, REASON_NO_FOREGROUND)
        )
      }
      null -> RecordingRepository.setIdle(host.message(RecordingMessage.FOREGROUND))
    }
    host.finishService()
  }

  // Live work.

  private suspend fun watchConnection(s: Live) {
    // The monitor owns the recorded connect/reconnect/disconnect events; these lines are only for
    // the diagnostics log.
    var everConnected = false
    var wasConnected = false
    s.sensor.connection.collect { state ->
      s.monitor.onLink(state)
      if (state == ConnectionState.CONNECTED) {
        Diagnostics.log(if (everConnected) "ble_reconnect" else "ble_connect", mapOf("sensor" to s.sensor.name))
        everConnected = true
        wasConnected = true
      } else if (wasConnected) {
        Diagnostics.log("ble_disconnect", mapOf("state" to state.name.lowercase()))
        wasConnected = false
      }
    }
  }

  /** Watches the radio so a recording that lost Bluetooth reconnects as soon as it is back. */
  private suspend fun watchBluetooth(s: Live) {
    BluetoothPower.enabled.collect { enabled ->
      if (enabled == null) return@collect
      for (step in s.monitor.onBluetooth(enabled)) applyRecovery(s, step)
    }
  }

  private suspend fun tickLoop(s: Live) {
    var lastHeartbeat = 0L
    var lastReadinessCheck = 0L
    var readiness = host.readinessSummary()
    while (true) {
      // The recording may have been ended or rebuilt by another path: only the live session ticks.
      if (live !== s) return
      val nowNs = clock.elapsedNs()
      val nowMs = nowNs / NS_PER_MS

      // The monitor derives everything; the control flow only feeds it the sensor's raw values and
      // performs the recovery steps it asks for. A step never ends the recording.
      s.writer.syncIfDue(nowMs)
      s.monitor.onBattery(s.sensor.battery.value)
      for (step in s.monitor.tick(nowNs)) applyRecovery(s, step)

      // One night is longer than one wake-lock timeout, so the lock is taken again before it lapses.
      val lock = s.wakeLock
      if (lock != null && RecordingRecovery.wakeLockNeedsRenewal(nowMs - s.wakeLockSinceMs)) {
        lock.acquire(RecordingRecovery.WAKE_LOCK_MS)
        s.wakeLockSinceMs = nowMs
      }

      if (nowMs - lastHeartbeat >= HEARTBEAT_MS) {
        lastHeartbeat = nowMs
        host.heartbeatMs = clock.phoneNs() / NS_PER_MS
      }
      if (nowMs - lastReadinessCheck >= READINESS_MS) {
        lastReadinessCheck = nowMs
        val now = host.readinessSummary()
        if (now != readiness) s.writer.event("readiness", now)
        readiness = now
      }
      delay(1000)
    }
  }

  /**
   * Performs the step the monitor's recovery policy asked for. A failure is recorded and never ends
   * the recording; the policy asks again on its next step.
   */
  private suspend fun applyRecovery(s: Live, step: RecoveryStep) {
    if (live !== s) return
    val cfg = s.writer.meta.config
    try {
      executeRecovery(step, s.sensor, StreamConfig(cfg.ecg, cfg.acc), s.fanout)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      s.writer.event("error", "recovery step failed: ${e.message ?: e.javaClass.simpleName}")
    }
  }

  companion object {
    private const val TAG = "RecordingControl"
    private const val NS_PER_MS = 1_000_000L
    private const val STOP_STREAMS_TIMEOUT_MS = 3_000L
    private const val HEARTBEAT_MS = 15_000L
    private const val READINESS_MS = 10_000L

    /** Written to the recording when the system refuses the foreground state. */
    const val REASON_NO_FOREGROUND = "the system refused to keep the service in the foreground"
  }
}
