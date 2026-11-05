package com.rbmr.taigasleep

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.guard.Readiness
import com.rbmr.taigasleep.guard.ReadinessState
import com.rbmr.taigasleep.recording.RecordingHooks
import com.rbmr.taigasleep.sync.RawRetention
import com.rbmr.taigasleep.sync.UploadScheduler
import com.rbmr.taigasleep.tracker.RecordingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaigaApplication : Application() {
  private var startedActivities = 0

  override fun onCreate() {
    super.onCreate()
    // Before anything can start a recording: the service asks these to decide and to hand over.
    RecordingHooks.install(RecordingWiring)
    Diagnostics.init(this)
    registerActivityLifecycleCallbacks(lifecycleLogger)
    DebugTools.install(this)
    CoroutineScope(Dispatchers.IO).launch {
      // Apply the raw-retention setting to any recording whose cleanup time has passed.
      RawRetention.enforce(this@TaigaApplication)
      UploadScheduler.cancelLegacyPolls(this@TaigaApplication)
    }
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

/**
 * Answers what the recording service asks of the rest of the app, so `recording` does not depend on
 * `guard` or `sync`: the start gate is the readiness Home shows, and a finished recording goes to
 * the upload queue.
 */
private object RecordingWiring : RecordingHooks {
  override fun mayStart(context: Context, mode: RecordingMode): Boolean =
      ReadinessState.ready(context, GuidanceStore(context), preview = null, mode = mode)

  override fun readinessSummary(context: Context): String = Readiness.read(context).summary()

  override fun onRecordingFinished(context: Context, recordingId: String) =
      UploadScheduler.onRecordingFinished(context, recordingId)
}
