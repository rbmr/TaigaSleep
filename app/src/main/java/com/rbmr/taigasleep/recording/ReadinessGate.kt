package com.rbmr.taigasleep.recording

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** The three phone settings that decide whether a night survives. See the background findings. */
data class Readiness(
    val ignoringBatteryOptimizations: Boolean,
    val standbyBucket: Int,
    val backgroundRestricted: Boolean,
) {
  val bucketExempted: Boolean
    get() = standbyBucket == ReadinessGate.BUCKET_EXEMPTED

  val ok: Boolean
    get() = ignoringBatteryOptimizations && bucketExempted && !backgroundRestricted

  fun summary() =
      "ignoring_battery_optimizations=$ignoringBatteryOptimizations " +
          "standby_bucket=$standbyBucket background_restricted=$backgroundRestricted"
}

object ReadinessGate {
  /** `STANDBY_BUCKET_EXEMPTED`, a hidden constant in the SDK. */
  const val BUCKET_EXEMPTED = 5

  fun check(context: Context): Readiness {
    val pm = context.getSystemService(PowerManager::class.java)
    val usm = context.getSystemService(UsageStatsManager::class.java)
    val am = context.getSystemService(ActivityManager::class.java)
    return Readiness(
        ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
        standbyBucket = usm.appStandbyBucket,
        backgroundRestricted = am.isBackgroundRestricted,
    )
  }

  private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

  /** The standard "let app always run in background?" popup. */
  fun batteryOptimizationIntent(context: Context) =
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** App info, where the vendor's battery / power consumption setting lives. */
  fun appSettingsIntent(context: Context) =
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
