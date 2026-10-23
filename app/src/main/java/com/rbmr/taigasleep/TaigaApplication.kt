package com.rbmr.taigasleep

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rbmr.taigasleep.diagnostics.Diagnostics

class TaigaApplication : Application() {
  private var startedActivities = 0

  override fun onCreate() {
    super.onCreate()
    Diagnostics.init(this)
    registerActivityLifecycleCallbacks(lifecycleLogger)
    if (BuildConfig.DEBUG) registerDebugTriggers()
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    Diagnostics.onTrimMemory(level)
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onLowMemory() {
    super.onLowMemory()
    Diagnostics.onLowMemory()
  }

  private val lifecycleLogger =
      object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
          Diagnostics.markRunning()
          logActivity(activity, "created")
        }

        override fun onActivityStarted(activity: Activity) {
          if (startedActivities++ == 0) {
            Diagnostics.log("app_foreground")
            Diagnostics.checkState("app_foreground")
          }
        }

        override fun onActivityStopped(activity: Activity) {
          if (--startedActivities == 0) {
            Diagnostics.log("app_background")
            Diagnostics.checkState("app_background")
          }
        }

        override fun onActivityDestroyed(activity: Activity) {
          logActivity(activity, "destroyed")
          // Finishing with no visible activity left is the user leaving the app on purpose.
          if (activity.isFinishing && startedActivities == 0) {
            Diagnostics.markCleanShutdown("activity_finished")
          }
        }

        override fun onActivityResumed(activity: Activity) = logActivity(activity, "resumed")

        override fun onActivityPaused(activity: Activity) = logActivity(activity, "paused")

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      }

  private fun logActivity(activity: Activity, state: String) {
    Diagnostics.log(
        "activity",
        mapOf(
            "name" to activity.javaClass.simpleName,
            "state" to state,
            "finishing" to activity.isFinishing,
            "changing_config" to activity.isChangingConfigurations,
        ),
    )
  }

  /** Debug builds only: `adb shell am broadcast -a com.rbmr.taigasleep.DEBUG_CRASH`. */
  private fun registerDebugTriggers() {
    ContextCompat.registerReceiver(
        this,
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

  private companion object {
    const val ACTION_DEBUG_CRASH = "com.rbmr.taigasleep.DEBUG_CRASH"
  }
}
