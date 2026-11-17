@file:Suppress("ReturnCount")

package com.rbmr.taigasleep.tracker.hrs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID

/** One event from a GATT connection, independent of the Android callback classes. */
sealed interface GattEvent {
  val handle: GattHandle

  data class Connected(override val handle: GattHandle) : GattEvent

  data class Disconnected(override val handle: GattHandle, val status: Int) : GattEvent

  data class ServicesDiscovered(override val handle: GattHandle, val status: Int) : GattEvent

  data class CharacteristicRead(
      override val handle: GattHandle,
      val characteristic: UUID,
      val value: ByteArray,
      val status: Int,
  ) : GattEvent

  data class DescriptorWritten(
      override val handle: GattHandle,
      val descriptor: UUID,
      val status: Int,
  ) : GattEvent

  data class CharacteristicChanged(
      override val handle: GattHandle,
      val characteristic: UUID,
      val value: ByteArray,
  ) : GattEvent
}

/** The GATT operations the heart-rate tracker uses, behind one testable seam. */
interface GattHandle {
  fun hasService(service: UUID): Boolean

  fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean

  fun discoverServices(): Boolean

  /** Enables or disables notifications, including the CCCD write. */
  fun setNotifications(service: UUID, characteristic: UUID, descriptor: UUID, enable: Boolean): Boolean

  fun readCharacteristic(service: UUID, characteristic: UUID): Boolean

  fun disconnect()

  fun close()
}

/** Creates a GATT connection; the real one wraps [BluetoothGatt]. */
interface GattSeam {
  fun isEnabled(): Boolean

  fun connect(address: String, onEvent: (GattEvent) -> Unit): GattHandle?
}

@SuppressLint("MissingPermission")
class AndroidGattSeam(private val context: Context) : GattSeam {
  override fun isEnabled(): Boolean =
      context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

  override fun connect(address: String, onEvent: (GattEvent) -> Unit): GattHandle? {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
    if (!adapter.isEnabled) return null
    val handle = RealGattHandle(onEvent)
    val gatt = adapter.getRemoteDevice(address).connectGatt(context, false, handle.callback) ?: return null
    handle.attach(gatt)
    return handle
  }
}

@SuppressLint("MissingPermission")
private class RealGattHandle(private val onEvent: (GattEvent) -> Unit) : GattHandle {
  @Volatile private var gatt: BluetoothGatt? = null

  fun attach(value: BluetoothGatt) {
    gatt = value
  }

  override fun hasService(service: UUID): Boolean = gatt?.getService(service) != null

  override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
      gatt?.getService(service)?.getCharacteristic(characteristic) != null

  override fun discoverServices(): Boolean = gatt?.discoverServices() == true

  override fun setNotifications(
      service: UUID,
      characteristic: UUID,
      descriptor: UUID,
      enable: Boolean,
  ): Boolean {
    val g = gatt ?: return false
    val value = g.getService(service)?.getCharacteristic(characteristic) ?: return false
    if (!g.setCharacteristicNotification(value, enable)) return false
    val cccd = value.getDescriptor(descriptor) ?: return false
    @Suppress("DEPRECATION")
    cccd.value =
        if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
    @Suppress("DEPRECATION")
    return g.writeDescriptor(cccd)
  }

  override fun readCharacteristic(service: UUID, characteristic: UUID): Boolean {
    val value = gatt?.getService(service)?.getCharacteristic(characteristic) ?: return false
    return gatt?.readCharacteristic(value) == true
  }

  override fun disconnect() {
    runCatching { gatt?.disconnect() }
  }

  override fun close() {
    runCatching { gatt?.close() }
    gatt = null
  }

  val callback =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          when (newState) {
            BluetoothProfile.STATE_CONNECTED -> onEvent(GattEvent.Connected(this@RealGattHandle))
            BluetoothProfile.STATE_DISCONNECTED ->
                onEvent(GattEvent.Disconnected(this@RealGattHandle, status))
          }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          onEvent(GattEvent.ServicesDiscovered(this@RealGattHandle, status))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
          onEvent(
              GattEvent.CharacteristicRead(this@RealGattHandle, characteristic.uuid, value, status)
          )
        }

        @Deprecated("The API before 33 reports the value here.")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
          if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            onEvent(
                GattEvent.CharacteristicRead(
                    this@RealGattHandle,
                    characteristic.uuid,
                    value,
                    status,
                )
            )
          }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
          onEvent(GattEvent.DescriptorWritten(this@RealGattHandle, descriptor.uuid, status))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
          onEvent(
              GattEvent.CharacteristicChanged(this@RealGattHandle, characteristic.uuid, value)
          )
        }

        @Deprecated("The API before 33 reports the value here.")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
          if (Build.VERSION.SDK_INT < 33) {
            @Suppress("DEPRECATION")
            characteristic.value?.let {
              onEvent(
                  GattEvent.CharacteristicChanged(
                      this@RealGattHandle,
                      characteristic.uuid,
                      it,
                  )
              )
            }
          }
        }
      }
}
