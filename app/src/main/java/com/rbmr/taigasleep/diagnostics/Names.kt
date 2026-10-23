package com.rbmr.taigasleep.diagnostics

/** Human-readable names for the integer constants the platform reports. */
object Names {
  fun exitReason(code: Int): String =
      when (code) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$code"
      }

  /** Exit reasons that carry a trace stream worth reading. */
  fun hasTrace(code: Int): Boolean = code == 4 || code == 5 || code == 6

  fun importance(code: Int): String =
      when (code) {
        100 -> "FOREGROUND"
        125 -> "FOREGROUND_SERVICE"
        150 -> "TOP_SLEEPING_OLD"
        200 -> "VISIBLE"
        230 -> "PERCEPTIBLE"
        270 -> "CANT_SAVE_STATE"
        300 -> "SERVICE"
        325 -> "TOP_SLEEPING"
        400 -> "CACHED"
        1000 -> "GONE"
        else -> "IMPORTANCE_$code"
      }

  fun standbyBucket(code: Int): String =
      when (code) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        else -> "BUCKET_$code"
      }

  fun thermal(code: Int): String =
      when (code) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "THERMAL_$code"
      }

  fun trimLevel(level: Int): String =
      when (level) {
        5 -> "RUNNING_MODERATE"
        10 -> "RUNNING_LOW"
        15 -> "RUNNING_CRITICAL"
        20 -> "UI_HIDDEN"
        40 -> "BACKGROUND"
        60 -> "MODERATE"
        80 -> "COMPLETE"
        else -> "TRIM_$level"
      }

  fun plugged(code: Int): String =
      when (code) {
        0 -> "none"
        1 -> "ac"
        2 -> "usb"
        4 -> "wireless"
        8 -> "dock"
        else -> "plugged_$code"
      }

  fun batteryHealth(code: Int): String =
      when (code) {
        2 -> "good"
        3 -> "overheat"
        4 -> "dead"
        5 -> "over_voltage"
        6 -> "failure"
        7 -> "cold"
        else -> "unknown_$code"
      }

  fun batteryStatus(code: Int): String =
      when (code) {
        2 -> "charging"
        3 -> "discharging"
        4 -> "not_charging"
        5 -> "full"
        else -> "unknown_$code"
      }

  fun bluetoothState(code: Int): String =
      when (code) {
        10 -> "OFF"
        11 -> "TURNING_ON"
        12 -> "ON"
        13 -> "TURNING_OFF"
        else -> "STATE_$code"
      }
}
