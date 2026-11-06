package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences
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

  /** ECG and ACC are remembered per device; both default to on. */
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

  companion object {
    private const val KEY_ACTIVE = "active_session"
    private const val KEY_HEARTBEAT = "heartbeat_ms"
    private const val KEY_SENSOR_KIND = "sensor_kind"
    private const val KEY_SENSOR_ID = "sensor_id"
    private const val KEY_SENSOR_NAME = "sensor_name"

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
