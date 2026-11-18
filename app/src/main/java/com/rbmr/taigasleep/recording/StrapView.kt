package com.rbmr.taigasleep.recording

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build

/**
 * The phone's own view of a strap's link, in one line for diagnostics: its GATT state, whether the
 * phone lists it, whether it is bonded, and — where Android exposes it — whether a client on the
 * phone holds its LE link. That last one is what a strap that stops advertising looks like.
 *
 * Null [manager] means the phone has no Bluetooth service to ask; the answer then says so instead.
 */
internal fun phoneStrapView(
    manager: BluetoothManager?,
    sensorId: String,
    sensorName: String,
    knownAddress: String?,
): String {
  if (manager == null) return "phone sees the strap: no Bluetooth service to ask"
  return try {
    strapLinkView(manager, sensorId, sensorName, knownAddress)
  } catch (_: SecurityException) {
    "phone sees the strap: not readable without the Bluetooth permission"
  } catch (_: IllegalArgumentException) {
    "phone sees the strap: the resolved device address is not valid"
  }
}

@SuppressLint("MissingPermission")
private fun strapLinkView(
    manager: BluetoothManager,
    sensorId: String,
    sensorName: String,
    knownAddress: String?,
): String {
  val connected = manager.getConnectedDevices(BluetoothProfile.GATT)
  val bonded = manager.adapter?.bondedDevices.orEmpty()
  val resolved =
      strapDeviceAddress(
          knownAddress = knownAddress,
          sensorId = sensorId,
          connected = connected.map { ListedDevice(it.address, it.name) },
          bonded = bonded.map { ListedDevice(it.address, it.name) },
      )
  val adapter = manager.adapter
  return if (resolved == null) {
    "phone sees the strap: no device for $sensorName ($sensorId) among its connected or " +
        "bonded devices"
  } else if (adapter == null) {
    "phone sees the strap: no Bluetooth adapter"
  } else {
    deviceView(manager, adapter.getRemoteDevice(resolved), connected, bonded)
  }
}

@SuppressLint("MissingPermission")
private fun deviceView(
    manager: BluetoothManager,
    device: BluetoothDevice,
    connected: List<BluetoothDevice>,
    bonded: Set<BluetoothDevice>,
): String {
  val gatt = manager.getConnectionState(device, BluetoothProfile.GATT)
  val listed = connected.any { it.address.equals(device.address, ignoreCase = true) }
  val paired = bonded.any { it.address.equals(device.address, ignoreCase = true) }
  return "phone sees the strap at ${device.address}: gatt ${gattName(gatt)}, " +
      (if (listed) "listed in the phone's connected devices"
      else "not listed in the phone's connected devices") +
      (if (paired) ", bonded" else ", not bonded") +
      ", " +
      clientLink(device)
}

/** Whether Bluetooth is on, or null when the phone cannot be asked. */
@SuppressLint("MissingPermission")
internal fun bluetoothOn(manager: BluetoothManager?): Boolean? =
    runCatching { manager?.adapter?.isEnabled }.getOrNull()

/**
 * Whether any client on the phone holds the strap's LE link. Android exposes that from 36.1; the
 * call is guarded because the class method is absent on the releases before it.
 */
@SuppressLint("MissingPermission", "NewApi")
private fun clientLink(device: BluetoothDevice): String {
  if (Build.VERSION.SDK_INT < 36) return "the client link is not exposed by this Android"
  return when (runCatching { device.isConnected(BluetoothDevice.TRANSPORT_LE) }.getOrNull()) {
    true -> "a client holds the LE link"
    false -> "no client holds the LE link"
    null -> "the client link is unreadable"
  }
}

internal fun gattName(state: Int): String =
    when (state) {
      BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
      BluetoothProfile.STATE_CONNECTING -> "connecting"
      BluetoothProfile.STATE_CONNECTED -> "connected"
      BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
      else -> "state $state"
    }
