package com.rbmr.taigasleep.debug

import android.app.Application
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SleepSensor

/** Release twin of the debug-only tools in `src/debug`: the same API, and nothing behind it. */
object DebugTools {
  const val GUIDANCE_PREVIEW = false

  val simulatedSensor: FoundSensor? = null

  fun createSimulatedSensor(): SleepSensor? = null

  fun install(app: Application) = Unit
}
