package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind

/** The sensor the user picked last, so it can be reconnected without scanning again. */
data class SavedSensor(val kind: SensorKind, val id: String, val name: String)

/** Small persisted state of the recorder: tracking config, last sensor and the live-recording marker. */
class RecordingPrefs private constructor(private val prefs: SharedPreferences) {
  /** Set while a recording is live; the watchdog and service restarts key off it. */
  var activeSessionId: String?
    get() = prefs.getString(KEY_ACTIVE, null)
    set(value) = prefs.edit().apply { if (value == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value) }.apply()

  /** Wall-clock ms of the service's last sign of life. */
  var heartbeatMs: Long
    get() = prefs.getLong(KEY_HEARTBEAT, 0)
    set(value) = prefs.edit().putLong(KEY_HEARTBEAT, value).apply()

  var lastSensor: SavedSensor?
    get() {
      val kind = SensorKind.fromWire(prefs.getString(KEY_SENSOR_KIND, null)) ?: return null
      val id = prefs.getString(KEY_SENSOR_ID, null) ?: return null
      return SavedSensor(kind, id, prefs.getString(KEY_SENSOR_NAME, null) ?: id)
    }
    set(value) =
        prefs
            .edit()
            .apply {
              if (value == null) {
                remove(KEY_SENSOR_KIND).remove(KEY_SENSOR_ID).remove(KEY_SENSOR_NAME)
              } else {
                putString(KEY_SENSOR_KIND, value.kind.wire)
                putString(KEY_SENSOR_ID, value.id)
                putString(KEY_SENSOR_NAME, value.name)
              }
            }
            .apply()

  /** The streams are remembered per device; all default to on. */
  fun hrEnabled(sensorId: String) = prefs.getBoolean("hr_$sensorId", true)

  fun setHrEnabled(sensorId: String, on: Boolean) =
      prefs.edit().putBoolean("hr_$sensorId", on).apply()

  fun ecgEnabled(sensorId: String) = prefs.getBoolean("ecg_$sensorId", true)

  fun accEnabled(sensorId: String) = prefs.getBoolean("acc_$sensorId", true)

  fun setEcgEnabled(sensorId: String, on: Boolean) =
      prefs.edit().putBoolean("ecg_$sensorId", on).apply()

  fun setAccEnabled(sensorId: String, on: Boolean) =
      prefs.edit().putBoolean("acc_$sensorId", on).apply()

  /** How this tracker records: streamed to the phone or stored on the tracker. */
  fun mode(sensorId: String): RecordingMode =
      RecordingMode.fromWire(prefs.getString("mode_$sensorId", null)) ?: RecordingMode.STREAM

  fun setMode(sensorId: String, mode: RecordingMode) =
      prefs.edit().putString("mode_$sensorId", mode.wire).apply()

  /** The wake-up window set for this tracker, or null when it is off (the default). */
  fun wakeWindow(sensorId: String): WakeWindow? =
      window(
          prefs.getInt("wake_start_$sensorId", -1),
          prefs.getInt("wake_len_$sensorId", -1),
          prefs.getInt("wake_end_$sensorId", -1),
          prefs.getBoolean("wake_hr_$sensorId", true),
          prefs.getBoolean("wake_move_$sensorId", false),
      )

  fun setWakeWindow(sensorId: String, window: WakeWindow?) =
      prefs.edit {
        if (window == null) {
          remove("wake_start_$sensorId")
          remove("wake_len_$sensorId")
          remove("wake_end_$sensorId")
          remove("wake_hr_$sensorId")
          remove("wake_move_$sensorId")
        } else {
          putInt("wake_start_$sensorId", window.startMinute)
          putInt("wake_len_$sensorId", window.lengthMinutes)
          remove("wake_end_$sensorId")
          putBoolean("wake_hr_$sensorId", window.heartRate)
          putBoolean("wake_move_$sensorId", window.movement)
        }
      }

  /**
   * The wake-up window armed for the running recording, with the bounds of its night. It survives a
   * process kill, so a resumed recording rings for the same night; it is cleared once the alarm rang
   * or the recording ended.
   */
  fun armedWake(): ArmedWake? {
    val recordingId = prefs.getString(KEY_WAKE_RECORDING, null) ?: return null
    val window =
        window(
            prefs.getInt(KEY_WAKE_START, -1),
            prefs.getInt(KEY_WAKE_LEN, -1),
            prefs.getInt(KEY_WAKE_END, -1),
            prefs.getBoolean(KEY_WAKE_HR, true),
            prefs.getBoolean(KEY_WAKE_MOVE, false),
        ) ?: return null
    val start = prefs.getLong(KEY_WAKE_START_MS, 0)
    val end = prefs.getLong(KEY_WAKE_END_MS, 0)
    if (start <= 0 || end <= start) return null
    return ArmedWake(recordingId, window, start, end)
  }

  fun setArmedWake(armed: ArmedWake) =
      prefs.edit {
        putString(KEY_WAKE_RECORDING, armed.recordingId)
        putInt(KEY_WAKE_START, armed.window.startMinute)
        putInt(KEY_WAKE_LEN, armed.window.lengthMinutes)
        remove(KEY_WAKE_END)
        putBoolean(KEY_WAKE_HR, armed.window.heartRate)
        putBoolean(KEY_WAKE_MOVE, armed.window.movement)
        putLong(KEY_WAKE_START_MS, armed.startUtcMs)
        putLong(KEY_WAKE_END_MS, armed.endUtcMs)
      }

  fun clearArmedWake() =
      prefs.edit {
        remove(KEY_WAKE_RECORDING)
        remove(KEY_WAKE_START)
        remove(KEY_WAKE_LEN)
        remove(KEY_WAKE_END)
        remove(KEY_WAKE_HR)
        remove(KEY_WAKE_MOVE)
        remove(KEY_WAKE_START_MS)
        remove(KEY_WAKE_END_MS)
      }

  /**
   * A stored window, or null when what is stored is not a window this version understands. Windows
   * saved before they had a length hold an end time instead; it is converted to a length here.
   */
  private fun window(
      startMinute: Int,
      lengthMinutes: Int,
      legacyEndMinute: Int,
      heartRate: Boolean,
      movement: Boolean,
  ): WakeWindow? =
      when {
        startMinute !in WakeWindow.MINUTES_OF_DAY -> null
        lengthMinutes in WakeWindow.LENGTHS ->
            WakeWindow(startMinute, lengthMinutes, heartRate, movement)
        else -> WakeWindow.between(startMinute, legacyEndMinute, heartRate, movement)
      }

  companion object {
    private const val KEY_ACTIVE = "active_session"
    private const val KEY_HEARTBEAT = "heartbeat_ms"
    private const val KEY_SENSOR_KIND = "sensor_kind"
    private const val KEY_SENSOR_ID = "sensor_id"
    private const val KEY_SENSOR_NAME = "sensor_name"
    private const val KEY_WAKE_RECORDING = "wake_recording"
    private const val KEY_WAKE_START = "wake_start"
    private const val KEY_WAKE_LEN = "wake_len"
    private const val KEY_WAKE_END = "wake_end"
    private const val KEY_WAKE_HR = "wake_hr"
    private const val KEY_WAKE_MOVE = "wake_move"
    private const val KEY_WAKE_START_MS = "wake_start_ms"
    private const val KEY_WAKE_END_MS = "wake_end_ms"

    @Volatile private var instance: RecordingPrefs? = null

    fun get(context: Context): RecordingPrefs =
        instance
            ?: synchronized(this) {
              instance
                  ?: RecordingPrefs(
                          context.applicationContext.getSharedPreferences("recording", Context.MODE_PRIVATE)
                      )
                      .also { instance = it }
            }
  }
}
