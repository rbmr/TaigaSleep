package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The global choice of what the wake-up alarm does with the running recording. */
class WakeAlarmSettings private constructor(private val prefs: SharedPreferences) {
  private val current = MutableStateFlow(read())

  /** The choice as it changes, for the screens that show it. */
  val endRecording: StateFlow<EndRecordingOnAlarm> = current.asStateFlow()

  var endRecordingOnAlarm: EndRecordingOnAlarm
    get() = read()
    set(value) {
      prefs.edit().putString(KEY_END_RECORDING, value.wire).apply()
      current.value = value
    }

  private fun read(): EndRecordingOnAlarm =
      EndRecordingOnAlarm.fromWire(prefs.getString(KEY_END_RECORDING, null))

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
