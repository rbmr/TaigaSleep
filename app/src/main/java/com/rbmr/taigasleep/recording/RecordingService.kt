package com.rbmr.taigasleep.recording

import android.Manifest
import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.notifications.DeviceAlerts
import com.rbmr.taigasleep.notifications.Notifier
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SystemClockSource
import kotlinx.coroutines.launch

/**
 * Foreground service that runs one recording. It survives the screen being off (partial wake
 * lock, the connected device foreground type and, while sound is recorded, the microphone one),
 * restarts after an OS kill (START_STICKY, null intent) and is watched by an exact-alarm
 * [WatchdogReceiver].
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
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Must happen on every start, including restarts by the OS, or the system kills the service.
    val type = recordingForegroundType(recordsSound(intent) && mayUseMicrophone())
    val granted = promote(notification(getString(R.string.rec_notification_starting)), type)
    Diagnostics.log(
        "service_start",
        mapOf(
            "null_intent" to (intent == null),
            "action" to intent?.action,
            "foreground_type" to granted?.let(::foregroundTypeName),
            "foreground" to (granted != null),
        ),
    )
    if (granted == null) {
      // Without the foreground state the system would kill the service at once, so the recording
      // cannot go on. It ends here instead of a sticky restart running into the same refusal.
      control.foregroundRefused()
      return START_NOT_STICKY
    }
    when (intent?.action) {
      ACTION_START -> control.start(startRequest(intent))
      ACTION_STOP -> control.stop()
      ACTION_FORCE_STOP -> control.forceStop()
      ACTION_DISCARD -> control.discard()
      ACTION_FETCH -> control.fetchPending(intent.getStringExtra(EXTRA_RECORDING_ID))
      ACTION_CLEANUP -> control.retryTrackerRemovals()
      ACTION_WAKE -> control.changeWake(wakeOf(intent))
      ACTION_WAKE_RANG -> control.wakeRang(intent.getStringExtra(EXTRA_WAKE_SOURCE).orEmpty())
      ACTION_WAKE_FAILED -> control.wakeFailed(intent.getStringExtra(EXTRA_WAKE_REASON).orEmpty())
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
          selected = intent.getStringArrayListExtra(EXTRA_STREAMS)?.toSet().orEmpty(),
          mode = RecordingMode.fromWire(intent.getStringExtra(EXTRA_MODE)),
          // The wake-up window is part of this tracker's configuration, like its streams.
          wake = intent.getStringExtra(EXTRA_SENSOR_ID)?.let { prefs.wakeWindow(it) },
          // Sound is the phone's microphone, chosen with the tracker's other streams.
          sound = intent.getStringExtra(EXTRA_SENSOR_ID)?.let { prefs.sound(it) } ?: SoundChoice.OFF,
      )

  /** The window an [ACTION_WAKE] carries, or null when it switches the alarm off. */
  private fun wakeOf(intent: Intent): WakeWindow? {
    if (!intent.getBooleanExtra(EXTRA_WAKE_ON, false)) return null
    return WakeWindow(
        startMinute = intent.getIntExtra(EXTRA_WAKE_START, 0),
        lengthMinutes = intent.getIntExtra(EXTRA_WAKE_LENGTH, WakeWindow.LENGTHS.first),
        heartRate = intent.getBooleanExtra(EXTRA_WAKE_HR, true),
        movement = intent.getBooleanExtra(EXTRA_WAKE_MOVEMENT, false),
    )
  }

  /**
   * Whether the recording this start belongs to holds the `sound` stream. A start reads the chosen
   * codec for its tracker and needs a streamed night; every other action reads the recording that
   * is already open, because that is what the foreground state has to keep alive.
   */
  private fun recordsSound(intent: Intent?): Boolean =
      if (intent?.action == ACTION_START) {
        val id = intent.getStringExtra(EXTRA_SENSOR_ID)
        val mode = RecordingMode.fromWire(intent.getStringExtra(EXTRA_MODE))
        val streamed = mode != RecordingMode.ON_DEVICE
        id != null && streamed && prefs.sound(id) != SoundChoice.OFF
      } else {
        val id = prefs.activeSessionId
        id != null && RecordingMeta.read(store.dir(id))?.stream(SoundChoice.SOUND_STREAM_ID) != null
      }

  /** The microphone can only be a foreground type while the app may use it. */
  private fun mayUseMicrophone(): Boolean =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

  // The Android side of the recording.

  override fun obtain(kind: SensorKind, id: String, name: String): Tracker =
      appContainer.sensorHub.obtain(this, kind, id, name)

  override fun mayStart(mode: RecordingMode): Boolean = hooks.mayStart(this, mode)

  override fun readinessSummary(): String = hooks.readinessSummary(this)

  override fun strapView(sensorId: String, sensorName: String, address: String?): String =
      phoneStrapView(
          manager = getSystemService(BluetoothManager::class.java),
          sensorId = sensorId,
          sensorName = sensorName,
          knownAddress = address,
      )

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

  override fun fireWakeAlarm(reason: WakeReason) =
      WakeAlarm.fire(this, reason, WakeAlarmService.SOURCE_SIGNAL)

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

  override fun refreshRecordings() {
    appContainer.scope.launch { RecordingRepository.refreshRecordings(this@RecordingService) }
  }

  override fun onRecordingFinished(recordingId: String) = hooks.onRecordingFinished(this, recordingId)

  override fun onFetchCompleted(recordingId: String) = hooks.onFetchCompleted(this, recordingId)

  override fun finishService() {
    lastNotificationText = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  // Notification. The text is formatted from the same RecordingState the screen reads.

  private var lastNotificationText: String? = null

  private val alerts by lazy { DeviceAlerts(this) }

  override fun onState(state: RecordingState) {
    reportHealth(state)
    val text = notificationText(state)
    if (text == lastNotificationText) return
    lastNotificationText = text
    Notifier.post(this, Notifier.RECORDING_ID, notification(text, state.capture.startUtcMs))
  }

  /**
   * The battery reading and the free-space answer the monitor already has feed the two device
   * alerts; each is sent once per episode, so the every-second publish repeats neither.
   */
  private fun reportHealth(state: RecordingState) {
    state.power.percent?.let {
      alerts.trackerBattery(it, state.power.state == RecordingState.PowerState.LOW)
    }
    alerts.phoneStorage(state.storage.lowSpace)
  }

  /** The tracker's name and one status; the notification's own timer shows the duration. */
  private fun notificationText(s: RecordingState): String {
    val name = s.capture.sensorName.ifBlank { getString(R.string.rec_notification_device) }
    return getString(R.string.rec_notification_text, name, getString(notificationStatus(s)))
  }

  private fun notificationStatus(s: RecordingState): Int =
      when (s.overall.reason) {
        RecordingState.Reason.STORAGE_ERROR -> R.string.rec_notification_storage_error
        RecordingState.Reason.STORAGE_LOW -> R.string.rec_notification_storage_low
        RecordingState.Reason.LINK_LOST -> R.string.rec_notification_no_connection
        RecordingState.Reason.OFF_BODY -> R.string.rec_notification_no_hr
        RecordingState.Reason.STREAM_STALE ->
            if (s.streams.values.firstOrNull { it.status == RecordingState.StreamStatus.STALE }?.kind ==
                RecordingState.StreamKind.HR) {
              R.string.rec_notification_no_hr
            } else {
              R.string.rec_notification_no_data
            }
        RecordingState.Reason.ECG_POOR -> R.string.rec_notification_ecg_poor
        RecordingState.Reason.BATTERY_LOW -> R.string.rec_notification_battery_low
        null -> R.string.rec_notification_connected
      }

  /**
   * Puts the service in the foreground and returns the type the system granted, or null when it
   * refused the foreground state altogether. A resume from the background can be refused the
   * microphone alone: the tracker link is tried then, so the night goes on without sound.
   */
  private fun promote(n: Notification, types: Int): Int? {
    val link = recordingForegroundType(holdsSound = false)
    val granted = promoteAs(n, types) ?: if (types != link) promoteAs(n, link) else null
    if (granted != null && granted != types) {
      Diagnostics.log(
          "service_foreground_type",
          mapOf("requested" to foregroundTypeName(types), "granted" to foregroundTypeName(granted)),
      )
    }
    return granted
  }

  /** One call to the system; the type it accepted, or null when it refused the foreground state. */
  private fun promoteAs(n: Notification, types: Int): Int? =
      try {
        startForeground(Notifier.RECORDING_ID, n, types)
        types
      } catch (e: Exception) {
        Log.w(TAG, "the system refused the ${foregroundTypeName(types)} foreground service", e)
        null
      }

  private fun notification(
      text: String,
      startUtcMs: Long = RecordingRepository.status.value.startUtcMs,
  ): Notification =
      Notifier.recording(this, text, startUtcMs, Notifier.APP_ACTIVITY)

  companion object {
    private const val TAG = "RecordingService"

    const val ACTION_START = "com.rbmr.taigasleep.recording.START"
    const val ACTION_STOP = "com.rbmr.taigasleep.recording.STOP"
    const val ACTION_FORCE_STOP = "com.rbmr.taigasleep.recording.FORCE_STOP"
    const val ACTION_DISCARD = "com.rbmr.taigasleep.recording.DISCARD"
    const val ACTION_RESUME = "com.rbmr.taigasleep.recording.RESUME"
    const val ACTION_RESTART = "com.rbmr.taigasleep.recording.RESTART"
    const val ACTION_FETCH = "com.rbmr.taigasleep.recording.FETCH"
    const val ACTION_CLEANUP = "com.rbmr.taigasleep.recording.CLEANUP"
    const val ACTION_WAKE = "com.rbmr.taigasleep.recording.WAKE"
    const val ACTION_WAKE_RANG = "com.rbmr.taigasleep.recording.WAKE_RANG"
    const val ACTION_WAKE_FAILED = "com.rbmr.taigasleep.recording.WAKE_FAILED"

    private const val EXTRA_SENSOR_KIND = "sensor_kind"
    private const val EXTRA_SENSOR_ID = "sensor_id"
    private const val EXTRA_SENSOR_NAME = "sensor_name"
    private const val EXTRA_STREAMS = "streams"
    private const val EXTRA_MODE = "mode"
    private const val EXTRA_RECORDING_ID = "recording_id"
    private const val EXTRA_WAKE_ON = "wake_on"
    private const val EXTRA_WAKE_START = "wake_start"
    private const val EXTRA_WAKE_LENGTH = "wake_length"
    private const val EXTRA_WAKE_HR = "wake_hr"
    private const val EXTRA_WAKE_MOVEMENT = "wake_movement"
    private const val EXTRA_WAKE_SOURCE = "wake_source"
    private const val EXTRA_WAKE_REASON = "wake_failure"

    /** True while this process has a live recording. */
    @Volatile
    var running = false
      private set

    fun intent(context: Context, action: String) =
        Intent(context, RecordingService::class.java).setAction(action)

    fun start(
        context: Context,
        sensor: Tracker,
        selected: Set<String>,
        mode: RecordingMode = RecordingMode.STREAM,
    ) {
      val i =
          intent(context, ACTION_START)
              .putExtra(EXTRA_SENSOR_KIND, sensor.kind.wire)
              .putExtra(EXTRA_SENSOR_ID, sensor.id)
              .putExtra(EXTRA_SENSOR_NAME, sensor.name)
              .putStringArrayListExtra(EXTRA_STREAMS, ArrayList(selected))
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

    /** Moves the smart alarm of the running recording to [window], or switches it off with null. */
    fun changeWake(context: Context, window: WakeWindow?) {
      val i = intent(context, ACTION_WAKE).putExtra(EXTRA_WAKE_ON, window != null)
      if (window != null) {
        i.putExtra(EXTRA_WAKE_START, window.startMinute)
            .putExtra(EXTRA_WAKE_LENGTH, window.lengthMinutes)
            .putExtra(EXTRA_WAKE_HR, window.heartRate)
            .putExtra(EXTRA_WAKE_MOVEMENT, window.movement)
      }
      context.startForegroundService(i)
    }

    /** The ringing service says the sound started; the recording notes it. */
    fun wakeRang(context: Context, source: String) {
      context.startForegroundService(
          intent(context, ACTION_WAKE_RANG).putExtra(EXTRA_WAKE_SOURCE, source)
      )
    }

    /** The ringing service could not sound the alarm; the recording notes why. */
    fun wakeFailed(context: Context, reason: String) {
      context.startForegroundService(
          intent(context, ACTION_WAKE_FAILED).putExtra(EXTRA_WAKE_REASON, reason)
      )
    }

    /** Retries the tracker copies that earlier removals left behind. */
    fun cleanup(context: Context) {
      context.startForegroundService(intent(context, ACTION_CLEANUP))
    }
  }
}

/**
 * The foreground service types of one recording: the tracker link always, and the microphone only
 * while the recording holds sound and the app may use it.
 */
internal fun recordingForegroundType(holdsSound: Boolean): Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
        (if (holdsSound) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)

/** The `foreground_type` diagnostics value of [type], naming the types it holds. */
internal fun foregroundTypeName(type: Int): String =
    if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
      "connectedDevice|microphone"
    } else {
      "connectedDevice"
    }
