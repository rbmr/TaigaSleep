package com.rbmr.taigasleep.diagnostics

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale

/** The maker families the app has device guidance for. */
enum class MakerFamily(val id: String) {
  OPLUS("oplus"),
  SAMSUNG("samsung"),
  OTHER("other"),
}

/**
 * The maker of this phone and the manufacturer or brand name that named it, so a caller can tell a
 * brand the guidance was tested on from one that shares its family.
 */
data class Maker(val family: MakerFamily, val matched: String?)

/**
 * The phone's own state: the sticky battery intent, the battery manager's counters, the power
 * manager's flags, the app's standby bucket and the maker family. This is the one place that reads
 * them, so the diagnostics, the recording cost and the preventing-shutdown flow cannot disagree.
 *
 * A read throws what the system throws; the caller guards the ones that can fail.
 */
@Suppress("TooManyFunctions")
object PhoneState {
  /** Sticky battery state; no health samples, just the phone's own battery. */
  fun battery(context: Context): Map<String, Any?>? {
    val intent =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    return linkedMapOf(
        "level_pct" to if (level >= 0 && scale > 0) level * 100 / scale else -1,
        "status" to Names.batteryStatus(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
        "plugged" to Names.plugged(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
        "temperature_c" to intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
        "health" to Names.batteryHealth(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 1)),
    )
  }

  /** The charge percentage of [battery], the one figure the heartbeat and the cost share. */
  fun batteryPercent(battery: Map<*, *>?): Int? =
      (battery?.get("level_pct") as? Int)?.takeIf { it in 0..100 }

  /** Whether [battery] says the phone is charging, as the heartbeat's `charging` records it. */
  fun charging(battery: Map<*, *>?): Boolean? =
      (battery?.get("status") as? String)?.let { it == "charging" || it == "full" }

  fun chargeCounterUah(context: Context): Long? =
      batteryManager(context)
          ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
          ?.takeIf { it != Long.MIN_VALUE }

  fun currentNowUa(context: Context): Long? =
      batteryManager(context)
          ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
          ?.takeIf { it != Int.MIN_VALUE }
          ?.toLong()

  fun energyNwh(context: Context): Long? =
      batteryManager(context)
          ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
          ?.takeIf { it != Long.MIN_VALUE }

  fun ignoringBatteryOptimizations(context: Context): Boolean =
      power(context).isIgnoringBatteryOptimizations(context.packageName)

  fun batterySaverOn(context: Context): Boolean = power(context).isPowerSaveMode

  fun dozing(context: Context): Boolean = power(context).isDeviceIdleMode

  fun interactive(context: Context): Boolean = power(context).isInteractive

  fun thermalStatus(context: Context): Int = power(context).currentThermalStatus

  fun thermalHeadroom(context: Context): Float? =
      try {
        power(context).getThermalHeadroom(0)
      } catch (_: Exception) {
        null
      }

  fun keyguardLocked(context: Context): Boolean =
      (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

  fun backgroundRestricted(context: Context): Boolean =
      (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

  fun standbyBucket(context: Context): Int =
      (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket

  fun maker(): Maker = maker(Build.MANUFACTURER, Build.BRAND)

  /** The family named by the manufacturer or brand, and the name that matched it. */
  fun maker(manufacturer: String?, brand: String?): Maker {
    val matched =
        listOf(manufacturer, brand)
            .mapNotNull { it?.trim()?.lowercase(Locale.ROOT) }
            .firstOrNull { it in OPLUS_BRANDS || it in SAMSUNG_BRANDS }
    val id = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase(Locale.ROOT)
    val family =
        when {
          matched == null -> familyOf(id)
          matched in OPLUS_BRANDS -> MakerFamily.OPLUS
          else -> MakerFamily.SAMSUNG
        }
    return Maker(family, matched)
  }

  private fun familyOf(id: String): MakerFamily =
      when {
        "oneplus" in id || "oppo" in id || "realme" in id -> MakerFamily.OPLUS
        "samsung" in id -> MakerFamily.SAMSUNG
        else -> MakerFamily.OTHER
      }

  private val OPLUS_BRANDS = setOf("oneplus", "oppo", "realme")

  private val SAMSUNG_BRANDS = setOf("samsung")

  private fun power(context: Context): PowerManager =
      context.getSystemService(Context.POWER_SERVICE) as PowerManager

  private fun batteryManager(context: Context): BatteryManager? =
      context.getSystemService(BatteryManager::class.java)
}
