package com.rbmr.taigasleep.recording

/** One Bluetooth device as the phone lists it: enough to find a strap by its name. */
internal data class ListedDevice(val address: String, val name: String?)

/**
 * The phone's Bluetooth address for a recording's strap.
 *
 * The address the sensor learned from the SDK wins when there is one. A Polar device id is not an
 * address, so without it the strap is found by its name: an H10 calls itself "Polar H10" followed by
 * its device id. Connected devices are searched before bonded ones, so the link the phone currently
 * holds is preferred over a stale pairing.
 *
 * Returns null when neither source names the strap, which is itself worth recording.
 */
internal fun strapDeviceAddress(
    knownAddress: String?,
    sensorId: String,
    connected: List<ListedDevice>,
    bonded: List<ListedDevice>,
): String? {
  if (!knownAddress.isNullOrBlank()) return knownAddress
  return (connected + bonded).firstOrNull { device ->
    device.name?.endsWith(sensorId, ignoreCase = true) == true
  }?.address
}
