package com.rbmr.taigasleep.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.BluetoothPower
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.EventLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.OnDeviceTracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import com.rbmr.taigasleep.tracker.SystemClockSource
import java.io.File
import java.io.IOException
import java.util.TimeZone
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that runs one recording. It survives the screen being off (partial wake
 * lock, `connectedDevice` type), restarts after an OS kill (START_STICKY, null intent) and is
 * watched by an exact-alarm [WatchdogReceiver].
 *
 * All lifecycle work runs on one thread; sample writes come from the sensor's own threads.
 */
class RecordingService : Service() {
  private val control = Executors.newSingleThreadExecutor { Thread(it, "recording-control") }
  private val scope = CoroutineScope(SupervisorJob() + control.asCoroutineDispatcher())

  private lateinit var store: RecordingStore
  private lateinit var prefs: RecordingPrefs
  private lateinit var hooks: RecordingHooks

  private class Live(
      val writer: RecordingWriter,
      val sensor: SleepSensor,
      val mode: RecordingMode,
      val lifecycle: RecordingLifecycle,
      val fanout: SampleFanout,
      val wakeLock: PowerManager.WakeLock?,
      val monitor: RecordingMonitor,
  ) {
    var jobs = emptyList<Job>()

    /** Elapsed-realtime ms when the wake lock was last acquired, so it can be renewed in time. */
    var wakeLockSinceMs = 0L
  }

  private var live: Live? = null

  override fun onCreate() {
    super.onCreate()
    store = appContainer.recordingStore
    prefs = RecordingPrefs.get(this)
    hooks = RecordingHooks.current()
    getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.rec_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must happen on every start, including restarts by the OS, or the system kills the service.
    val foreground = promote(notification(getString(R.string.rec_notification_starting)))
    Diagnostics.log(
        "service_start",
        mapOf(
            "null_intent" to (intent == null),
            "action" to intent?.action,
            "foreground_type" to "connectedDevice",
            "foreground" to foreground,
        ),
    )
    if (!foreground) {
      // Without the foreground state the system would kill the service at once, so the recording
      // cannot go on. It ends here instead of a sticky restart running into the same refusal.
      scope.launch { foregroundRefused() }
      return START_NOT_STICKY
    }
    when (intent?.action) {
      ACTION_START -> scope.launch { start(intent) }
      ACTION_STOP -> scope.launch { stop(forced = false) }
      ACTION_FORCE_STOP -> scope.launch { stop(forced = true) }
      ACTION_DISCARD -> scope.launch { discard() }
      ACTION_FETCH -> scope.launch { fetchPending(intent.getStringExtra(EXTRA_RECORDING_ID)) }
      ACTION_CLEANUP -> scope.launch { retryTrackerRemovals() }
      ACTION_RESTART -> scope.launch { restart() }
      // ACTION_RESUME from the watchdog, or a null intent after the OS restarted the service.
      else -> scope.launch { resume() }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    Diagnostics.logSync("service_destroy", mapOf("recording" to (live != null)))
    live?.let {
      // Destroyed without a stop request: leave meta.json at `recording` so the watchdog or the
      // next app start can resume or mark it.
      it.writer.sync()
      it.writer.close()
      releaseWakeLock(it)
    }
    live = null
    scope.cancel()
    control.shutdown()
    super.onDestroy()
  }

  // Lifecycle. Each runs on the control thread.

  private suspend fun start(intent: Intent) {
    if (live != null) return
    val kind = SensorKind.fromWire(intent.getStringExtra(EXTRA_SENSOR_KIND))
    val sensorId = intent.getStringExtra(EXTRA_SENSOR_ID)
    if (kind == null || sensorId == null) {
      leave(getString(R.string.rec_error_no_sensor))
      return
    }
    val sensor =
        try {
          appContainer.sensorHub.obtain(this, kind, sensorId, intent.getStringExtra(EXTRA_SENSOR_NAME) ?: sensorId)
        } catch (e: IllegalStateException) {
          leave(e.message ?: getString(R.string.rec_error_no_sensor))
          return
        }
    val config =
        RecordingConfig(
            ecg = intent.getBooleanExtra(EXTRA_ECG, false) && sensor.capabilities.ecg,
            acc = intent.getBooleanExtra(EXTRA_ACC, false) && sensor.capabilities.acc,
        )
    val modes = sensor.recordingModes(StreamConfig(config.ecg, config.acc))
    if (modes.isEmpty()) {
      leave(getString(R.string.rec_error_mode))
      return
    }
    val requested = RecordingMode.fromWire(intent.getStringExtra(EXTRA_MODE))
    val mode =
        when {
          requested != null && requested in modes -> requested
          RecordingMode.STREAM in modes -> RecordingMode.STREAM
          else -> modes.first()
        }
    // The same answer Home shows, from the flow the user picked: the app wiring supplies it. An
    // on-device recording skips the battery steps because the phone may sleep.
    if (!hooks.mayStart(this, mode)) {
      Log.w(TAG, "start refused: ${hooks.readinessSummary(this)}")
      leave(getString(R.string.rec_error_not_ready))
      return
    }
    // A night that cannot fit would fill the phone and lose the rest of it anyway.
    if (!StorageSpace.canStart(PhoneSpace(store.root).availableBytes(), config)) {
      Log.w(TAG, "start refused: not enough free space")
      leave(getString(R.string.rec_error_space))
      return
    }
    val now = System.currentTimeMillis()
    val meta =
        RecordingMeta(
            recordingId = store.newRecordingId(now),
            state = RecordingPhase.RECORDING,
            mode = mode,
            startUtcMs = now,
            endUtcMs = null,
            timezone = TimeZone.getDefault().id,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            phoneManufacturer = Build.MANUFACTURER,
            phoneModel = Build.MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            sensorKind = sensor.kind.wire,
            sensorId = sensor.id,
            sensorName = sensor.name,
            sensorFirmware = sensor.firmware.value,
            sensorBatteryAtStart = sensor.battery.value,
            config = config,
        )
    val writer =
        try {
          RecordingWriter.create(store.root, meta, SystemClockSource)
        } catch (e: Exception) {
          Log.e(TAG, "cannot create recording", e)
          leave(getString(R.string.rec_error_storage))
          return
        }
    writer.event("readiness", hooks.readinessSummary(this))
    attach(writer, sensor, mode)
  }

  /**
   * The watchdog found the service alive but its heartbeat stopped: its live session has to be
   * rebuilt, because a resume would find [live] set and do nothing. The streams are dropped without
   * finishing the recording, which is then attached again on the same directory.
   */
  private suspend fun restart() {
    live?.let {
      Diagnostics.log("service_restart", mapOf("recording" to it.writer.meta.recordingId))
      teardown(it)
    }
    resume()
  }

  /**
   * Drops the live session without ending the recording: its streams stop and its logs are closed,
   * so the directory can be opened again. meta.json stays as it is, so the recording stays open.
   */
  private suspend fun teardown(s: Live) {
    s.jobs.forEach { it.cancel() }
    apply(s, s.lifecycle.restart())
    s.writer.sync()
    s.writer.close()
    releaseWakeLock(s)
    live = null
    running = false
  }

  /** Picks up the recording that was running before this process or service instance died. */
  private suspend fun resume(contact: Boolean = true) {
    if (live != null) return
    val id = prefs.activeSessionId
    val dir = id?.let { store.dir(it) }
    val meta = dir?.let { RecordingMeta.read(it) }
    val kind = SensorKind.fromWire(meta?.sensorKind)
    if (dir == null || meta == null || kind == null || meta.state == RecordingPhase.STOPPED ||
        meta.state == RecordingPhase.FORCED_STOP) {
      prefs.activeSessionId = null
      WatchdogReceiver.cancel(this)
      leave(null)
      return
    }
    val writer = RecordingWriter.resume(dir, SystemClockSource, "service resumed recording") ?: run {
      leave(null)
      return
    }
    writer.event("readiness", hooks.readinessSummary(this))
    val sensor =
        try {
          appContainer.sensorHub.obtain(this, kind, meta.sensorId, meta.sensorName)
        } catch (e: IllegalStateException) {
          writer.event("error", e.message ?: "sensor unavailable")
          writer.finish(RecordingPhase.INTERRUPTED, "cannot resume")
          prefs.activeSessionId = null
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
    val acquiredAtMs = SystemClock.elapsedRealtime()
    val wakeLock =
        if (needsWakeLock) {
          getSystemService(PowerManager::class.java)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaigaSleep::recording")
              .apply {
                setReferenceCounted(false)
                acquire(RecordingRecovery.WAKE_LOCK_MS)
              }
        } else {
          null
        }
    val monitor =
        RecordingMonitor(
            meta = writer.meta,
            clock = SystemClockSource,
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
    running = true
    lastNotificationText = null
    prefs.activeSessionId = writer.meta.recordingId
    prefs.heartbeatMs = System.currentTimeMillis()
    // An on-device recording needs no watchdog: nothing on the phone has to keep running.
    if (mode == RecordingMode.STREAM) WatchdogReceiver.schedule(this)
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
        tracker.startOnDevice(streamConfig, fanout) { error -> scope.launch { onDeviceStarted(error) } }
      }
    } else {
      sensor.startStreams(streamConfig, fanout)
    }
    if (contact && sensor.connection.value == ConnectionState.DISCONNECTED) sensor.connect()

    s.jobs =
        buildList {
          // One collector feeds both consumers, so the screen and the notification never disagree.
          add(
              scope.launch {
                monitor.state.collect { state ->
                  RecordingRepository.update { it.copy(state = state) }
                  showState(state)
                }
              }
          )
          add(scope.launch { watchConnection(s) })
          add(scope.launch { tickLoop(s) })
          // A streamed recording is the only one whose link can be lost with the radio.
          if (mode == RecordingMode.STREAM) add(scope.launch { watchBluetooth(s) })
        }
    RecordingRepository.refreshRecordings(this)
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
    running = false
    WatchdogReceiver.cancel(this)
    releaseWakeLock(s)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  /** Picks up the on-device recording that is running on the tracker, after the service was gone. */
  private suspend fun adopt(contact: Boolean): Live? {
    if (live == null) resume(contact = contact)
    return live
  }

  private suspend fun stop(forced: Boolean) {
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
    when (val result = OnDeviceImport(s.writer, tracker, SystemClockSource).fetch()) {
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
  private suspend fun fetchPending(recordingId: String?) {
    if (live != null) return
    val id = recordingId ?: store.fetchPending()?.recordingId
    val dir = id?.let { store.dir(it) }
    val meta = dir?.let { RecordingMeta.read(it) }
    if (dir == null || meta == null || !meta.fetchPending) {
      leave(getString(R.string.rec_error_fetch_missing))
      return
    }
    val kind = SensorKind.fromWire(meta.sensorKind)
    val tracker =
        if (kind == null) {
          null
        } else {
          runCatching { appContainer.sensorHub.obtain(this, kind, meta.sensorId, meta.sensorName) }
              .getOrNull() as? OnDeviceTracker
        }
    if (tracker == null) {
      leavePending(meta, getString(R.string.rec_error_fetch_failed))
      return
    }
    val writer = RecordingWriter.resumeFetch(dir, SystemClockSource, "fetching from the tracker")
    if (writer == null) {
      // The recording is there but could not be reopened from a consistent state; the event log
      // says what the half-written entry was. It stays fetch pending for another attempt.
      leavePending(meta, getString(R.string.rec_error_fetch_recovery))
      return
    }
    when (val result = OnDeviceImport(writer, tracker, SystemClockSource).fetch()) {
      is OnDeviceFetch.Imported -> {
        if (!writer.markTrackerRemoval(result.entries.map { it.id })) {
          writer.event("error", "the tracker copy could not be remembered for removal")
        }
        writer.finish(RecordingPhase.STOPPED, "recording fetched from the tracker")
        report(TrackerCleanup(dir, tracker).retry(), writer::event)
        RecordingRepository.setIdle()
        hooks.onFetchCompleted(this, meta.recordingId)
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
  private fun retryTrackerRemovals() {
    if (live != null) return
    for (meta in store.pendingTrackerRemovals()) {
      if (live != null) break
      val kind = SensorKind.fromWire(meta.sensorKind) ?: continue
      val tracker =
          runCatching { appContainer.sensorHub.obtain(this, kind, meta.sensorId, meta.sensorName) }
              .getOrNull() as? OnDeviceTracker ?: continue
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
      EventLog.append(dir, SystemClockSource.phoneNs(), SystemClockSource.elapsedNs(), type, message)
    } catch (e: IOException) {
      Log.w(TAG, "event write failed", e)
    }
  }

  /** The cleanup action is done; it changes nothing the screen shows. */
  private fun finishCleanup() {
    lastNotificationText = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
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
    running = false
    lastNotificationText = null
    prefs.activeSessionId = null
    WatchdogReceiver.cancel(this)
    RecordingRepository.refreshRecordings(this)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private suspend fun discard() {
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
    running = false
    lastNotificationText = null
    prefs.activeSessionId = null
    WatchdogReceiver.cancel(this)
    releaseWakeLock(s)
    if (pending != null) RecordingRepository.setFetchPending(pending) else RecordingRepository.setIdle()
    RecordingRepository.refreshRecordings(this)
    if (pending == null &&
        s.lifecycle.state != LifecycleState.DISCARDED &&
        s.writer.meta.state != RecordingPhase.RECORDING) {
      hooks.onRecordingFinished(this, s.writer.meta.recordingId)
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  /** Nothing to record: drop the foreground state again, optionally reporting why. */
  private fun leave(error: String?) {
    RecordingRepository.setIdle(error)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun releaseWakeLock(s: Live) {
    s.wakeLock?.takeIf { it.isHeld }?.release()
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
    var readiness = hooks.readinessSummary(this)
    while (true) {
      // The recording may have been ended or rebuilt by another path: only the live session ticks.
      if (live !== s) return
      val nowMs = SystemClock.elapsedRealtime()
      val nowNs = SystemClockSource.elapsedNs()

      // The monitor derives everything; the service only feeds it the sensor's raw values and
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
        prefs.heartbeatMs = System.currentTimeMillis()
      }
      if (nowMs - lastReadinessCheck >= READINESS_MS) {
        lastReadinessCheck = nowMs
        val now = hooks.readinessSummary(this)
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

  // Notification. The text is formatted from the same RecordingState the screen reads.

  private var lastNotificationText: String? = null

  private fun showState(state: RecordingState) {
    val text = notificationText(state)
    if (text == lastNotificationText) return
    lastNotificationText = text
    getSystemService(NotificationManager::class.java)
        .notify(NOTIFICATION_ID, notification(text, state.capture.startUtcMs))
  }

  private fun notificationText(s: RecordingState): String {
    val name = s.capture.sensorName.ifBlank { getString(R.string.rec_notification_device) }
    return when (s.overall.reason) {
      RecordingState.Reason.STORAGE_ERROR ->
          getString(R.string.rec_notification_storage_error, name)
      RecordingState.Reason.STORAGE_LOW ->
          getString(R.string.rec_notification_storage_low, name)
      RecordingState.Reason.LINK_LOST ->
          getString(
              when (s.link.state) {
                RecordingState.LinkState.CONNECTING -> R.string.rec_notification_reconnecting
                else -> R.string.rec_notification_disconnected
              },
              name,
          )
      RecordingState.Reason.OFF_BODY ->
          getString(
              R.string.rec_notification_off_body,
              name,
              RecordingFormat.duration(s.contact.offBodyMs),
          )
      RecordingState.Reason.STREAM_STALE ->
          getString(R.string.rec_notification_stalled, name, streamName(s))
      RecordingState.Reason.ECG_POOR -> getString(R.string.rec_notification_ecg_poor, name)
      RecordingState.Reason.BATTERY_LOW ->
          getString(R.string.rec_notification_battery_low, name, s.power.percent ?: 0)
      null -> {
        val rates = rateText(s)
        if (rates.isEmpty()) getString(R.string.rec_notification_connected, name)
        else
            getString(
                R.string.rec_notification_rates,
                getString(R.string.rec_notification_connected, name),
                rates,
            )
      }
    }
  }

  /** The measured stream rates, with the same rounding the active screen uses. */
  private fun rateText(s: RecordingState): String =
      s.streams.values
          .filter { it.kind != RecordingState.StreamKind.HR && it.rateHz != null }
          .joinToString(" · ") { RecordingFormat.rate(streamLabel(it.kind), it.rateHz!!) }

  private fun streamName(s: RecordingState): String =
      streamLabel(
          s.streams.values.firstOrNull { it.status == RecordingState.StreamStatus.STALE }?.kind
              ?: RecordingState.StreamKind.HR
      )

  private fun streamLabel(kind: RecordingState.StreamKind): String =
      getString(
          when (kind) {
            RecordingState.StreamKind.HR -> R.string.rec_active_hr
            RecordingState.StreamKind.ECG -> R.string.rec_active_ecg
            RecordingState.StreamKind.ACC -> R.string.rec_active_acc
          }
      )

  // Notification.

  /** Returns false when the system refuses the foreground state; the recording cannot go on then. */
  private fun promote(n: Notification): Boolean =
      try {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
      } catch (e: Exception) {
        Log.e(TAG, "the system refused the foreground service", e)
        false
      }

  /**
   * The system did not let this service run in the foreground, for example because the Bluetooth
   * permission was revoked or the start came from the background. The recording cannot be kept
   * alive, so it is closed with the reason on disk and on the screen, and nothing crashes.
   */
  private suspend fun foregroundRefused() {
    live?.let { teardown(it) }
    val id = RecordingRecovery.abandon(store, prefs.activeSessionId, REASON_NO_FOREGROUND, SystemClockSource)
    Diagnostics.log("service_foreground_refused", mapOf("recording" to id))
    if (id != null) {
      prefs.activeSessionId = null
      WatchdogReceiver.cancel(this)
      RecordingRepository.refreshRecordings(this)
      hooks.onRecordingFinished(this, id)
    }
    leave(getString(R.string.rec_error_foreground))
  }

  private fun notification(text: String, startUtcMs: Long = RecordingRepository.status.value.startUtcMs): Notification {
    val open =
        PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
    return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_recording)
        .setContentTitle(getString(R.string.rec_notification_title))
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .apply { if (startUtcMs > 0) setWhen(startUtcMs).setUsesChronometer(true) }
        .build()
  }

  companion object {
    private const val TAG = "RecordingService"
    private const val CHANNEL_ID = "recording"
    private const val NOTIFICATION_ID = 1
    private const val STOP_STREAMS_TIMEOUT_MS = 3_000L
    private const val HEARTBEAT_MS = 15_000L
    private const val READINESS_MS = 10_000L
    /** Written to the recording when the system refuses the foreground state. */
    private const val REASON_NO_FOREGROUND = "the system refused to keep the service in the foreground"

    const val ACTION_START = "com.rbmr.taigasleep.recording.START"
    const val ACTION_STOP = "com.rbmr.taigasleep.recording.STOP"
    const val ACTION_FORCE_STOP = "com.rbmr.taigasleep.recording.FORCE_STOP"
    const val ACTION_DISCARD = "com.rbmr.taigasleep.recording.DISCARD"
    const val ACTION_RESUME = "com.rbmr.taigasleep.recording.RESUME"
    const val ACTION_RESTART = "com.rbmr.taigasleep.recording.RESTART"
    const val ACTION_FETCH = "com.rbmr.taigasleep.recording.FETCH"
    const val ACTION_CLEANUP = "com.rbmr.taigasleep.recording.CLEANUP"

    private const val EXTRA_SENSOR_KIND = "sensor_kind"
    private const val EXTRA_SENSOR_ID = "sensor_id"
    private const val EXTRA_SENSOR_NAME = "sensor_name"
    private const val EXTRA_ECG = "ecg"
    private const val EXTRA_ACC = "acc"
    private const val EXTRA_MODE = "mode"
    private const val EXTRA_RECORDING_ID = "recording_id"

    /** True while this process has a live recording. */
    @Volatile
    var running = false
      private set

    fun intent(context: Context, action: String) =
        Intent(context, RecordingService::class.java).setAction(action)

    fun start(
        context: Context,
        sensor: SleepSensor,
        config: StreamConfig,
        mode: RecordingMode = RecordingMode.STREAM,
    ) {
      val i =
          intent(context, ACTION_START)
              .putExtra(EXTRA_SENSOR_KIND, sensor.kind.wire)
              .putExtra(EXTRA_SENSOR_ID, sensor.id)
              .putExtra(EXTRA_SENSOR_NAME, sensor.name)
              .putExtra(EXTRA_ECG, config.ecg)
              .putExtra(EXTRA_ACC, config.acc)
              .putExtra(EXTRA_MODE, mode.wire)
      context.startForegroundService(i)
    }

    fun send(context: Context, action: String) {
      context.startForegroundService(intent(context, action))
    }

    /** Tries the tracker fetch of a recording that is still waiting for it. */
    fun fetch(context: Context, recordingId: String) {
      context.startForegroundService(
          intent(context, ACTION_FETCH).putExtra(EXTRA_RECORDING_ID, recordingId)
      )
    }

    /** Retries the tracker copies that earlier removals left behind. */
    fun cleanup(context: Context) {
      context.startForegroundService(intent(context, ACTION_CLEANUP))
    }
  }
}
