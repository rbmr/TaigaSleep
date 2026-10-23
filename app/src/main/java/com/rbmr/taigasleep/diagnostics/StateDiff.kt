package com.rbmr.taigasleep.diagnostics

/** Compares two reads of [DeviceState.volatileState] so a change is logged with old and new values. */
object StateDiff {
  /** Fields that change what the system may do to us; a change also takes a full snapshot. */
  val CRITICAL = setOf("bucket", "whitelisted", "background_restricted", "exact_alarms", "power_save")

  /** The keys [DeviceState.volatileState] reports, used to pick them out of a heartbeat. */
  val KEYS =
      listOf(
          "bucket",
          "whitelisted",
          "background_restricted",
          "exact_alarms",
          "power_save",
          "doze",
          "interactive",
          "locked",
          "plugged",
          "charging",
      )

  /** Every key whose value differs, as `key to [old, new]`; keys missing on one side count as null. */
  fun diff(old: Map<String, Any?>, new: Map<String, Any?>): Map<String, List<Any?>> {
    val changes = linkedMapOf<String, List<Any?>>()
    for (key in (old.keys + new.keys)) {
      if (old[key] != new[key]) changes[key] = listOf(old[key], new[key])
    }
    return changes
  }
}
