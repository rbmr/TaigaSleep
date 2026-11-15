package com.rbmr.taigasleep.recording

import android.content.Context
import com.rbmr.taigasleep.notifications.DeviceAlerts
import com.rbmr.taigasleep.recording.store.RecordingConfig

/**
 * The phone's free space as the app checks it outside a running recording: when the app comes to
 * the front and when a sync pass ends, the places the app already runs. It uses the same
 * [StorageSpace] threshold and the same answer the recording's own once-a-minute check produces.
 *
 * While a recording runs its monitor already checks this space, so this stands back then: one
 * answer, not two that could disagree.
 */
object PhoneStorageWatch {
  fun check(context: Context) {
    if (RecordingService.running) return
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val low = StorageSpace.isLow(PhoneSpace(base).availableBytes(), config(context))
    DeviceAlerts(context).phoneStorage(low)
  }

  /** The streams the user chose for the last tracker, which is the space the next recording needs. */
  private fun config(context: Context): RecordingConfig {
    val prefs = RecordingPrefs.get(context)
    val sensor = prefs.lastSensor ?: return RecordingConfig(ecg = true, acc = true)
    return RecordingConfig(
        hr = prefs.hrEnabled(sensor.id),
        ecg = prefs.ecgEnabled(sensor.id),
        acc = prefs.accEnabled(sensor.id),
    )
  }
}
