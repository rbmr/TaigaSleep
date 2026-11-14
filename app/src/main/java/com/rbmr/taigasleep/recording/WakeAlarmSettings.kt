package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences

/** The global choice of what the wake-up alarm does with the running recording. */
class WakeAlarmSettings private constructor(private val prefs: SharedPreferences) {
  var endRecordingOnAlarm: EndRecordingOnAlarm
    get() = EndRecordingOnAlarm.fromWire(prefs.getString(KEY_END_RECORDING, null))
    set(value) {
      prefs.edit().putString(KEY_END_RECORDING, value.wire).apply()
    }

  companion object {
    private const val KEY_END_RECORDING = "end_recording"

    @Volatile private var instance: WakeAlarmSettings? = null

    fun get(context: Context): WakeAlarmSettings =
        instance
            ?: synchronized(this) {
              instance
                  ?: WakeAlarmSettings(
                          context.applicationContext.getSharedPreferences(
                              "wake_alarm",
                              Context.MODE_PRIVATE,
                          )
                      )
                      .also { instance = it }
            }
  }
}
