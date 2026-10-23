package com.rbmr.taigasleep.diagnostics

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Reads device and app state for diagnostics. Every read is wrapped so one failing or
 * permission-gated API records "unreadable" instead of losing the whole line. Nothing here reads
 * health data, sensor data, accounts, location or other apps' packages.
 */
object DeviceState {
  const val UNREADABLE = "unreadable"

  private val PERMISSIONS_OF_INTEREST =
      listOf(
          "android.permission.BLUETOOTH_SCAN",
          "android.permission.BLUETOOTH_CONNECT",
          "android.permission.POST_NOTIFICATIONS",
          "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
      )

  // Best effort: absent keys are simply left out of the snapshot.
  private val COMMON_KEYS =
      listOf(
          "adaptive_battery_management_enabled",
          "app_standby_enabled",
          "low_power",
          "low_power_sticky",
          "device_idle_constants",
          "battery_saver_constants",
      )
  private val OPLUS_KEYS =
      listOf(
          "startup_app_list",
          "auto_startup",
          "oplus_startup_app_list",
          "startup_app_list_oplus",
      )
  private val SAMSUNG_KEYS =
      listOf(
          "psm_switch",
          "sem_low_power_mode",
          "app_sleeping_enabled",
          "auto_disable_unused_apps",
      )

  fun <T> safe(block: () -> T): Any? =
      try {
        block()
      } catch (_: Exception) {
        UNREADABLE
      }

  /**
   * The fields that decide whether the system may stop us, cheap enough to read on every event
   * and heartbeat. Diagnostics compares consecutive reads and logs what changed.
   */
  fun volatileState(context: Context): Map<String, Any?> {
    val battery = safe { battery(context) } as? Map<*, *>
    return linkedMapOf(
        "bucket" to safe { bucketName(context) },
        "whitelisted" to safe { power(context).isIgnoringBatteryOptimizations(context.packageName) },
        "background_restricted" to safe { activity(context).isBackgroundRestricted },
        "exact_alarms" to safe { alarms(context).canScheduleExactAlarms() },
        "power_save" to safe { power(context).isPowerSaveMode },
        "doze" to safe { power(context).isDeviceIdleMode },
        "interactive" to safe { power(context).isInteractive },
        "locked" to safe { keyguard(context).isKeyguardLocked },
        "plugged" to battery?.get("plugged"),
        "charging" to battery?.get("status")?.let { it == "charging" || it == "full" },
    )
  }

  /** Cheap state sampled every minute. */
  fun heartbeat(context: Context): Map<String, Any?> {
    val nowElapsed = SystemClock.elapsedRealtime()
    val battery = safe { battery(context) } as? Map<*, *>
    return linkedMapOf<String, Any?>(
        "uptime_s" to (nowElapsed - Process.getStartElapsedRealtime()) / 1000
    ).apply {
      putAll(volatileState(context))
      put("battery_pct", battery?.get("level_pct"))
      put("battery_temp_c", battery?.get("temperature_c"))
      put("thermal", safe { Names.thermal(power(context).currentThermalStatus) })
      put("importance", safe { processState()["importance"] })
      put("mem_avail_mb", safe { memory(context)["avail_mb"] })
      put("java_heap_kb", safe { javaHeapKb() })
      put("native_heap_kb", safe { Debug.getNativeHeapAllocatedSize() / 1024 })
      put("threads", safe { threadCount() })
    }
  }

  /** Full settings snapshot; see docs/diagnostics.md for the field list. */
  fun snapshot(context: Context): Map<String, Any?> =
      linkedMapOf(
          "sdk_int" to Build.VERSION.SDK_INT,
          "power" to
              linkedMapOf(
                  "ignoring_battery_optimizations" to
                      safe { power(context).isIgnoringBatteryOptimizations(context.packageName) },
                  "power_save_mode" to safe { power(context).isPowerSaveMode },
                  "device_idle_mode" to safe { power(context).isDeviceIdleMode },
                  "interactive" to safe { power(context).isInteractive },
                  "thermal_status" to safe { Names.thermal(power(context).currentThermalStatus) },
              ),
          "app" to
              linkedMapOf(
                  "standby_bucket" to safe { usage(context).appStandbyBucket },
                  "standby_bucket_name" to safe { bucketName(context) },
                  "background_restricted" to safe { activity(context).isBackgroundRestricted },
                  "notifications_enabled" to safe { notifications(context).areNotificationsEnabled() },
                  "can_schedule_exact_alarms" to safe { alarms(context).canScheduleExactAlarms() },
                  "process" to safe { processState() },
              ),
          "battery" to safe { battery(context) },
          "bluetooth" to linkedMapOf("adapter_state" to safe { bluetoothState(context) }),
          "system" to
              linkedMapOf(
                  "stay_on_while_plugged_in" to
                      safe {
                        Settings.Global.getInt(
                            context.contentResolver,
                            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                        )
                      },
                  "screen_off_timeout_ms" to
                      safe {
                        Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.SCREEN_OFF_TIMEOUT,
                        )
                      },
                  "locale" to Locale.getDefault().toLanguageTag(),
                  "timezone" to TimeZone.getDefault().id,
                  "timezone_offset_min" to TimeZone.getDefault().rawOffset / 60000,
              ),
          "memory" to safe { memory(context) },
          "permissions" to safe { permissions(context) },
          "vendor" to vendor(context),
      )

  fun memory(context: Context): Map<String, Any?> {
    val info = ActivityManager.MemoryInfo()
    activity(context).getMemoryInfo(info)
    return linkedMapOf(
        "avail_mb" to info.availMem / MB,
        "total_mb" to info.totalMem / MB,
        "threshold_mb" to info.threshold / MB,
        "low_memory" to info.lowMemory,
        "java_heap_kb" to safe { javaHeapKb() },
        "java_max_kb" to Runtime.getRuntime().maxMemory() / 1024,
        "native_heap_kb" to safe { Debug.getNativeHeapAllocatedSize() / 1024 },
        "threads" to safe { threadCount() },
    )
  }

  /** Memory facts for a crash line; must work in a dying process, so it avoids anything fancy. */
  fun crashMemory(context: Context?): Map<String, Any?> {
    val rt = Runtime.getRuntime()
    val fields =
        linkedMapOf<String, Any?>(
            "java_used_kb" to (rt.totalMemory() - rt.freeMemory()) / 1024,
            "java_max_kb" to rt.maxMemory() / 1024,
            "native_heap_kb" to safe { Debug.getNativeHeapAllocatedSize() / 1024 },
            "threads" to safe { threadCount() },
        )
    if (context != null) fields["system"] = safe { memory(context) }
    return fields
  }

  private fun vendor(context: Context): Map<String, Any?> {
    val id = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
    val family =
        when {
          "oneplus" in id || "oppo" in id || "realme" in id -> "oplus"
          "samsung" in id -> "samsung"
          else -> "other"
        }
    val keys =
        COMMON_KEYS +
            when (family) {
              "oplus" -> OPLUS_KEYS
              "samsung" -> SAMSUNG_KEYS
              else -> emptyList()
            }
    val values = linkedMapOf<String, Any?>()
    for (key in keys) readSetting(context, key)?.let { values[key] = it }
    return linkedMapOf("family" to family, "settings" to values)
  }

  /** First value found in Global, Secure, System; "unreadable" if all failed; null if absent. */
  private fun readSetting(context: Context, key: String): String? {
    var failed = false
    val cr = context.contentResolver
    val readers =
        listOf<() -> String?>(
            { Settings.Global.getString(cr, key) },
            { Settings.Secure.getString(cr, key) },
            { Settings.System.getString(cr, key) },
        )
    for (read in readers) {
      try {
        read()?.let { return it }
      } catch (_: Exception) {
        failed = true
      }
    }
    return if (failed) UNREADABLE else null
  }

  private fun permissions(context: Context): Map<String, Any?> {
    val pm = context.packageManager
    val declared =
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(
                PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions
            ?.toList() ?: emptyList()
    val granted = linkedMapOf<String, Any?>()
    for (name in PERMISSIONS_OF_INTEREST) {
      granted[name.substringAfterLast('.')] =
          context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    }
    return linkedMapOf(
        "granted" to granted,
        "declared" to declared.map { it.removePrefix("android.permission.") }.sorted(),
    )
  }

  private fun processState(): Map<String, Any?> {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return linkedMapOf(
        "importance" to Names.importance(info.importance),
        "importance_code" to info.importance,
        "last_trim_level" to Names.trimLevel(info.lastTrimLevel),
    )
  }

  /** Sticky battery state; no health samples, just the phone's own battery. */
  fun battery(context: Context): Map<String, Any?>? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null
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

  fun bluetoothState(context: Context): String {
    val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return if (adapter == null) "NO_ADAPTER" else Names.bluetoothState(adapter.state)
  }

  fun bucketName(context: Context): String = Names.standbyBucket(usage(context).appStandbyBucket)

  private fun javaHeapKb(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / 1024
  }

  /** Thread count from /proc, which is far cheaper than enumerating stack traces. */
  private fun threadCount(): Int =
      File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("Threads:") }.substringAfter(':').trim().toInt()
      }

  private const val MB = 1024L * 1024L

  private fun power(c: Context) = c.getSystemService(Context.POWER_SERVICE) as PowerManager

  private fun activity(c: Context) = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

  private fun usage(c: Context) =
      c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

  private fun keyguard(c: Context) = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

  private fun alarms(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  private fun notifications(c: Context) =
      c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
