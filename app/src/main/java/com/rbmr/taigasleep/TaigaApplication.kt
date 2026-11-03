package com.rbmr.taigasleep

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.upload.RawRetention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaigaApplication : Application() {
  private var startedActivities = 0

  override fun onCreate() {
    super.onCreate()
    Diagnostics.init(this)
    registerActivityLifecycleCallbacks(lifecycleLogger)
    DebugTools.install(this)
    // Apply the raw-retention setting to any recording whose cleanup time has passed.
    CoroutineScope(Dispatchers.IO).launch { RawRetention.enforce(this@TaigaApplication) }
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
}
