package com.rbmr.taigasleep.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.StreamConfig
import com.rbmr.taigasleep.tracker.SystemClockSource

/**
 * Foreground service that runs one recording. It survives the screen being off (partial wake
 * lock, `connectedDevice` type), restarts after an OS kill (START_STICKY, null intent) and is
 * watched by an exact-alarm [WatchdogReceiver].
 *
 * The service itself is the Android side: it gets the foreground state, builds the notification,
 * obtains the tracker and persists the marker. What it does with a recording — the ordered steps,
 * the recovery, the restart of a stalled control thread — is [RecordingControl], which is tested on
 * the JVM against a fake sensor and a fake clock.
 */
class RecordingService : Service(), RecordingHost {
  private lateinit var store: RecordingStore
  private lateinit var prefs: RecordingPrefs
  private lateinit var hooks: RecordingHooks
  private lateinit var control: RecordingControl

  override fun onCreate() {
    super.onCreate()
    store = appContainer.recordingStore
    prefs = RecordingPrefs.get(this)
    hooks = RecordingHooks.current()
    control = RecordingControl(store, SystemClockSource, this)
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
      control.foregroundRefused()
      return START_NOT_STICKY
    }
    when (intent?.action) {
      ACTION_START -> control.start(startRequest(intent))
      ACTION_STOP -> control.stop(forced = false)
      ACTION_FORCE_STOP -> control.stop(forced = true)
      ACTION_DISCARD -> control.discard()
      ACTION_FETCH -> control.fetchPending(intent.getStringExtra(EXTRA_RECORDING_ID))
      ACTION_CLEANUP -> control.retryTrackerRemovals()
      // The watchdog found the service alive but its control thread stalled: the restart runs on a
      // thread of its own, so it does not queue behind the thread it is recovering.
      ACTION_RESTART -> control.restart()
      // ACTION_RESUME from the watchdog, or a null intent after the OS restarted the service.
      else -> control.resume()
    }
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    Diagnostics.logSync("service_destroy", mapOf("recording" to control.hasLiveSession))
    control.shutdown()
    super.onDestroy()
  }

  private fun startRequest(intent: Intent) =
      StartRequest(
          kind = SensorKind.fromWire(intent.getStringExtra(EXTRA_SENSOR_KIND)),
          sensorId = intent.getStringExtra(EXTRA_SENSOR_ID),
          sensorName = intent.getStringExtra(EXTRA_SENSOR_NAME),
          ecg = intent.getBooleanExtra(EXTRA_ECG, false),
          acc = intent.getBooleanExtra(EXTRA_ACC, false),
          mode = RecordingMode.fromWire(intent.getStringExtra(EXTRA_MODE)),
          // The wake-up window is part of this tracker's configuration, like its streams.
          wake = intent.getStringExtra(EXTRA_SENSOR_ID)?.let { prefs.wakeWindow(it) },
      )

  // The Android side of the recording.

  override fun obtain(kind: SensorKind, id: String, name: String): SleepSensor =
      appContainer.sensorHub.obtain(this, kind, id, name)

  override fun mayStart(mode: RecordingMode): Boolean = hooks.mayStart(this, mode)

  override fun readinessSummary(): String = hooks.readinessSummary(this)

  override fun strapView(sensorId: String, sensorName: String, address: String?): String {
    val manager =
        getSystemService(BluetoothManager::class.java)
            ?: return "phone sees the strap: no Bluetooth service to ask"
    return try {
      val connected = manager.getConnectedDevices(BluetoothProfile.GATT)
      val bonded = manager.adapter?.bondedDevices.orEmpty()
      val resolved =
          strapDeviceAddress(
              knownAddress = address,
              sensorId = sensorId,
              connected = connected.map { ListedDevice(it.address, it.name) },
              bonded = bonded.map { ListedDevice(it.address, it.name) },
          )
      if (resolved == null) {
        "phone sees the strap: no device for $sensorName ($sensorId) among its connected or " +
            "bonded devices"
      } else {
        val device = manager.adapter?.getRemoteDevice(resolved)
        if (device == null) {
          "phone sees the strap: no Bluetooth adapter"
        } else {
          val gatt = manager.getConnectionState(device, BluetoothProfile.GATT)
          val listed = connected.any { it.address.equals(device.address, ignoreCase = true) }
          "phone sees the strap at $resolved: gatt ${gattName(gatt)}, " +
              if (listed) "listed in the phone's connected devices"
              else "not listed in the phone's connected devices"
        }
      }
    } catch (_: SecurityException) {
      "phone sees the strap: not readable without the Bluetooth permission"
    } catch (_: IllegalArgumentException) {
      "phone sees the strap: the resolved device address is not valid"
    }
  }

  override fun newWakeLock(): RecordingWakeLock? =
      getSystemService(PowerManager::class.java)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TaigaSleep::recording")
          .apply { setReferenceCounted(false) }
          .let { lock ->
            object : RecordingWakeLock {
              override val isHeld: Boolean
                get() = lock.isHeld

              override fun acquire(timeoutMs: Long) = lock.acquire(timeoutMs)

              override fun release() = lock.release()
            }
          }

  override fun scheduleWatchdog() = WatchdogReceiver.schedule(this)

  override fun cancelWatchdog() = WatchdogReceiver.cancel(this)

  override fun wakeWindow(): ArmedWake? = prefs.armedWake()

  override fun armWake(armed: ArmedWake) {
    // Persist before arming: a process killed between the two must find the window again.
    prefs.setArmedWake(armed)
    WakeAlarm.scheduleEnd(this, armed.endUtcMs)
  }

  override fun disarmWake() = WakeAlarm.disarm(this)

  override fun fireWakeAlarm() = WakeAlarm.fire(this)

  override var activeSessionId: String?
    get() = prefs.activeSessionId
    set(value) {
      prefs.activeSessionId = value
    }

  override var heartbeatMs: Long
    get() = prefs.heartbeatMs
    set(value) {
      prefs.heartbeatMs = value
    }

  override fun markRunning(running: Boolean) {
    RecordingService.running = running
  }

  override val device: RecordingDevice
    get() =
        RecordingDevice(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
        )

  override fun message(message: RecordingMessage): String =
      getString(
          when (message) {
            RecordingMessage.NO_SENSOR -> R.string.rec_error_no_sensor
            RecordingMessage.MODE -> R.string.rec_error_mode
            RecordingMessage.NOT_READY -> R.string.rec_error_not_ready
            RecordingMessage.SPACE -> R.string.rec_error_space
            RecordingMessage.STORAGE -> R.string.rec_error_storage
            RecordingMessage.FETCH_MISSING -> R.string.rec_error_fetch_missing
            RecordingMessage.FETCH_FAILED -> R.string.rec_error_fetch_failed
            RecordingMessage.FETCH_RECOVERY -> R.string.rec_error_fetch_recovery
            RecordingMessage.FOREGROUND -> R.string.rec_error_foreground
          }
      )

  override fun refreshRecordings() = RecordingRepository.refreshRecordings(this)

  override fun onRecordingFinished(recordingId: String) = hooks.onRecordingFinished(this, recordingId)

  override fun onFetchCompleted(recordingId: String) = hooks.onFetchCompleted(this, recordingId)

  override fun finishService() {
    lastNotificationText = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  // Notification. The text is formatted from the same RecordingState the screen reads.

  private var lastNotificationText: String? = null

  override fun onState(state: RecordingState) {
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

  /** Returns false when the system refuses the foreground state; the recording cannot go on then. */
  private fun promote(n: Notification): Boolean =
      try {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
      } catch (e: Exception) {
        Log.e(TAG, "the system refused the foreground service", e)
        false
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

    private fun gattName(state: Int): String =
        when (state) {
          BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
          BluetoothProfile.STATE_CONNECTING -> "connecting"
          BluetoothProfile.STATE_CONNECTED -> "connected"
          BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
          else -> "state $state"
        }

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
