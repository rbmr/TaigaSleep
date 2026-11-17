package com.rbmr.taigasleep.debug

import android.app.Application
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.FoundSensor
import java.io.File

/** Release twin of the debug-only tools in `src/debug`: the same API, and nothing behind it. */
object DebugTools {
  const val GUIDANCE_PREVIEW = false

  val fakeTracker: FoundSensor? = null

  @Suppress("FunctionOnlyReturningConstant")
  fun createFakeTracker(): Tracker? = null

  @Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
  fun replayTracker(dir: File): Tracker? = null

  fun startStrictMode() = Unit

  fun install(app: Application) = Unit
}
