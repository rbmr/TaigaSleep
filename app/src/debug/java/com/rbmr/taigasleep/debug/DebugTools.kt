package com.rbmr.taigasleep.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.core.content.ContextCompat
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.FakeTracker

/**
 * Tools that only exist in debug builds. `src/release` has a twin with the same API that does
 * nothing, so none of this ships in the release APK. docs/debug-build.md lists every difference.
 */
object DebugTools {
  /**
   * Long-pressing the preventing-shutdown card on Home picks which device's guidance to preview.
   */
  const val GUIDANCE_PREVIEW = true

  /** Listed next to real straps while scanning, so recording can be tried without hardware. */
  val simulatedSensor: FoundSensor? =
      FoundSensor(SensorKind.SIMULATED, "SIM00001", "Simulated sensor", 0)

  fun createSimulatedSensor(): SleepSensor? = FakeTracker()

  /**
   * Reports disk and network work on the main thread in the log (tag `StrictMode`), so a startup
   * that does too much shows it instead of only being slower. Installed before anything else in
   * `Application.onCreate`. Release builds have the empty twin and never run it.
   */
  fun startStrictMode() {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build()
    )
  }

  /** `adb shell am broadcast -a com.rbmr.taigasleep.DEBUG_CRASH` crashes the app on purpose. */
  fun install(app: Application) {
    ContextCompat.registerReceiver(
        app,
        DebugCrashReceiver(),
        IntentFilter(ACTION_DEBUG_CRASH),
        ContextCompat.RECEIVER_EXPORTED,
    )
  }

  private class DebugCrashReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      Handler(Looper.getMainLooper()).post {
        throw RuntimeException("Debug crash requested by broadcast")
      }
    }
  }

  private const val ACTION_DEBUG_CRASH = "com.rbmr.taigasleep.DEBUG_CRASH"
}
