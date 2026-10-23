package com.rbmr.taigasleep

import android.content.Context
import com.rbmr.taigasleep.guidance.PreviewDevice

class GuidanceStore(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("guidance", Context.MODE_PRIVATE)

  // Manual steps only: the user tapped "I have done this" for a step the app cannot read.
  fun isDone(flowId: String, stepId: String): Boolean =
      prefs.getBoolean(doneKey(flowId, stepId), false)

  fun setDone(flowId: String, stepId: String, done: Boolean) {
    prefs.edit().putBoolean(doneKey(flowId, stepId), done).apply()
  }

  // The flow the user picked when the detected one was wrong. Null means use the detected flow.
  var flowOverride: String?
    get() = prefs.getString(KEY_FLOW_OVERRIDE, null)
    set(value) = prefs.edit().putString(KEY_FLOW_OVERRIDE, value).apply()

  // Debug builds only: which device's guidance to preview. Null means the real device.
  var previewDevice: PreviewDevice?
    get() =
        prefs.getString(KEY_PREVIEW_DEVICE, null)?.let { name ->
          runCatching { PreviewDevice.valueOf(name) }.getOrNull()
        }
    set(value) = prefs.edit().putString(KEY_PREVIEW_DEVICE, value?.name).apply()

  companion object {
    private const val KEY_PREVIEW_DEVICE = "preview_device"
    private const val KEY_FLOW_OVERRIDE = "flow_override"

    fun doneKey(flowId: String, stepId: String): String = "done.$flowId.$stepId"
  }
}
