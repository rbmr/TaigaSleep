package com.rbmr.taigasleep.guidance

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager

// Raw answers from the system; null means the system did not answer.
data class ReadinessInputs(
    val ignoringBatteryOptimizations: Boolean,
    val standbyBucket: Int?,
    val backgroundRestricted: Boolean,
)

object ReadinessChecker {
  const val BUCKET_EXEMPTED = 5

  fun isBatteryExempted(context: Context): Boolean = batteryExempted(readInputs(context))

  fun isBackgroundAllowed(context: Context): Boolean = backgroundAllowed(readInputs(context))

  // Pure decisions, kept apart from the Android calls so they can be unit tested.
  fun batteryExempted(inputs: ReadinessInputs): Boolean = inputs.ignoringBatteryOptimizations

  // The verified effect of "Allow background activity" (OnePlus) and "Unrestricted" (Samsung):
  // whitelisted, exempted standby bucket and not background restricted.
  fun backgroundAllowed(inputs: ReadinessInputs): Boolean =
      inputs.ignoringBatteryOptimizations &&
          inputs.standbyBucket == BUCKET_EXEMPTED &&
          !inputs.backgroundRestricted

  private fun readInputs(context: Context): ReadinessInputs {
    val app = context.applicationContext
    val bucket =
        runCatching { app.getSystemService(UsageStatsManager::class.java).appStandbyBucket }
            .getOrNull()
    return ReadinessInputs(
        ignoringBatteryOptimizations =
            app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
        standbyBucket = bucket,
        backgroundRestricted = app.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
    )
  }
}
