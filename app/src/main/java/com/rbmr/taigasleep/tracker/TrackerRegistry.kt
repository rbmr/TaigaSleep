package com.rbmr.taigasleep.tracker

import com.rbmr.taigasleep.tracker.hrs.BleHrsDriver
import com.rbmr.taigasleep.tracker.polar.PolarH10Driver
import com.rbmr.taigasleep.tracker.polar.PolarLoopDriver
import com.rbmr.taigasleep.tracker.polar.PolarVerityDriver

/** What a driver needs to create its tracker: the app context and the device that matched. */
interface TrackerDriver {
  val kind: SensorKind

  /** True when this device is the kind of tracker this driver knows how to run. */
  fun matches(device: FoundSensor): Boolean

  /** Creates the tracker for a device [matches] accepted. */
  fun create(context: android.content.Context, device: FoundSensor): Tracker
}

/**
 * The one list of tracker drivers. Scanning asks each driver for devices; a found device is created
 * by the first driver that matches it, so adding a device is adding a driver.
 */
class TrackerRegistry(val drivers: List<TrackerDriver>) {
  /** The driver that runs this device, or null when no driver knows it. */
  fun driverFor(device: FoundSensor): TrackerDriver? =
      drivers.firstOrNull { it.matches(device) }

  /** Creates the tracker for [device], or throws when no driver knows it. */
  fun create(context: android.content.Context, device: FoundSensor): Tracker =
      checkNotNull(driverFor(device)) { "no driver for ${device.kind.wire}" }
          .create(context, device)

  companion object {
    /** The drivers the app ships with, in the order a matching device is offered to them. */
    fun default(): TrackerRegistry =
        TrackerRegistry(
            listOf(PolarH10Driver(), PolarVerityDriver(), PolarLoopDriver(), BleHrsDriver())
        )
  }
}
