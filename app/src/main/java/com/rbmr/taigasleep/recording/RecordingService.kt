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
import com.rbmr.taigasleep.diagnostics.Diagnostics
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
import java.util.TimeZone
import java.util.concurrent.Executors
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
    promote(notification(getString(R.string.rec_notification_starting)))
    Diagnostics.log(
        "service_start",
        mapOf("null_intent" to (intent == null), "action" to intent?.action, "foreground_type" to "connectedDevice"),
    )
    when (intent?.action) {
      ACTION_START -> scope.launch { start(intent) }
      ACTION_STOP -> scope.launch { stop(forced = false) }
      ACTION_FORCE_STOP -> scope.launch { stop(forced = true) }
      ACTION_DISCARD -> scope.launch { discard() }
      ACTION_FETCH -> scope.launch { fetchPending(intent.getStringExtra(EXTRA_RECORDING_ID)) }
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

  /** Picks up the recording that was running before this process or service instance died. */
  private suspend fun resume(parked: Boolean = false, contact: Boolean = true) {
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
    attach(writer, sensor, meta.mode, parked, contact)
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
    val wakeLock =
        if (needsWakeLock) {
          getSystemService(PowerManager::class.java)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaigaSleep::recording")
              .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
              }
        } else {
          null
        }
    val monitor =
        RecordingMonitor(
            meta = writer.meta,
            clock = SystemClockSource,
            event = writer::event,
            storageHealth = writer::storageHealth,
        )
    monitor.onPhase(RecordingState.Phase.RECORDING)
    monitor.onLink(sensor.connection.value)
    val fanout =
        SampleFanout(writer, monitor) { message -> writer.event("error", message) }
    val s = Live(writer, sensor, mode, lifecycle, fanout, wakeLock, monitor)
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
      if (!parked) {
        tracker.startOnDevice(streamConfig, fanout) { error -> scope.launch { onDeviceStarted(error) } }
      }
    } else {
      sensor.startStreams(streamConfig, fanout)
    }
    if (contact && sensor.connection.value == ConnectionState.DISCONNECTED) sensor.connect()

    s.jobs =
        listOf(
            // One collector feeds both consumers, so the screen and the notification never disagree.
            scope.launch {
              monitor.state.collect { state ->
                RecordingRepository.update { it.copy(state = state) }
                showState(state)
              }
            },
            scope.launch { watchConnection(s) },
            scope.launch { tickLoop(s) },
        )
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
    if (live == null) resume(parked = true, contact = contact)
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
        removeFromTracker(s, tracker, result)
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
      leavePending(meta, getString(R.string.rec_error_fetch_missing))
      return
    }
    when (val result = OnDeviceImport(writer, tracker, SystemClockSource).fetch()) {
      is OnDeviceFetch.Imported -> {
        removeFromTracker(writer, tracker, result)
        writer.finish(RecordingPhase.STOPPED, "recording fetched from the tracker")
        RecordingRepository.setIdle()
        hooks.onRecordingFinished(this, meta.recordingId)
      }
      is OnDeviceFetch.Failed -> {
        writer.event("error", "fetching the tracker recording failed: $result.reason")
        writer.finish(RecordingPhase.FETCH_PENDING, "fetch failed; the tracker keeps the recording")
        RecordingRepository.setFetchPending(
            PendingFetch(meta.recordingId, meta.sensorName, meta.startUtcMs, result.reason)
        )
      }
    }
    writer.close()
    finishFetchService()
  }

  private fun removeFromTracker(
      writer: RecordingWriter,
      tracker: OnDeviceTracker,
      result: OnDeviceFetch.Imported,
  ) {
    runCatching { tracker.removeFromDevice(result.entries) }
        .onFailure { writer.event("error", "could not remove the tracker copy: ${it.message}") }
  }

  private fun removeFromTracker(
      s: Live,
      tracker: OnDeviceTracker,
      result: OnDeviceFetch.Imported,
  ) = removeFromTracker(s.writer, tracker, result)

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
        LifecycleEffect.RemoveFromTracker -> Unit
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

  private suspend fun tickLoop(s: Live) {
    var lastHeartbeat = 0L
    var lastReadinessCheck = 0L
    var readiness = hooks.readinessSummary(this)
    while (true) {
      val nowMs = SystemClock.elapsedRealtime()
      val nowNs = SystemClockSource.elapsedNs()

      // The monitor derives everything; the service only feeds it the sensor's raw values.
      s.writer.syncIfDue(nowMs)
      s.monitor.onBattery(s.sensor.battery.value)
      s.monitor.tick(nowNs)

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
    val name = s.capture.sensorName.ifBlank { getString(R.string.rec_notification_title) }
    return when (s.overall.reason) {
      RecordingState.Reason.STORAGE_ERROR ->
          getString(R.string.rec_notification_storage_error, name)
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

  /** The measured stream rates, formatted exactly like the active screen shows them. */
  private fun rateText(s: RecordingState): String =
      s.streams.values
          .filter { it.kind != RecordingState.StreamKind.HR && it.rateHz != null }
          .joinToString(" · ") {
            val label =
                getString(
                    when (it.kind) {
                      RecordingState.StreamKind.HR -> R.string.rec_active_hr
                      RecordingState.StreamKind.ECG -> R.string.rec_active_ecg
                      RecordingState.StreamKind.ACC -> R.string.rec_active_acc
                    }
                )
            "$label ${Math.round(it.rateHz!! / 5) * 5} Hz"
          }

  private fun streamName(s: RecordingState): String {
    val kind =
        s.streams.values.firstOrNull { it.status == RecordingState.StreamStatus.STALE }?.kind
            ?: RecordingState.StreamKind.HR
    return getString(
        when (kind) {
          RecordingState.StreamKind.HR -> R.string.rec_active_hr
          RecordingState.StreamKind.ECG -> R.string.rec_active_ecg
          RecordingState.StreamKind.ACC -> R.string.rec_active_acc
        }
    )
  }

  // Notification.

  private fun promote(n: Notification) {
    startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
    private const val WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
    private const val STOP_STREAMS_TIMEOUT_MS = 3_000L
    private const val HEARTBEAT_MS = 15_000L
    private const val READINESS_MS = 10_000L

    const val ACTION_START = "com.rbmr.taigasleep.recording.START"
    const val ACTION_STOP = "com.rbmr.taigasleep.recording.STOP"
    const val ACTION_FORCE_STOP = "com.rbmr.taigasleep.recording.FORCE_STOP"
    const val ACTION_DISCARD = "com.rbmr.taigasleep.recording.DISCARD"
    const val ACTION_RESUME = "com.rbmr.taigasleep.recording.RESUME"
    const val ACTION_FETCH = "com.rbmr.taigasleep.recording.FETCH"

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
  }
}
