@file:Suppress("ReturnCount", "TooGenericExceptionCaught", "NestedBlockDepth")

package com.rbmr.taigasleep.tracker.hrs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.rbmr.taigasleep.tracker.BleHrsCodec
import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.tracker.SystemClockSource
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerCapabilities
import com.rbmr.taigasleep.tracker.TrackerDriver
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/** The generic Bluetooth Heart Rate Service: service 0x180D, measurement 0x2A37, battery 0x180F. */
object Hrs {
  val SERVICE: UUID = uuid(0x180D)
  val MEASUREMENT: UUID = uuid(0x2A37)
  val BATTERY_SERVICE: UUID = uuid(0x180F)
  val BATTERY_LEVEL: UUID = uuid(0x2A19)
  val DEVICE_INFORMATION: UUID = uuid(0x180A)
  val FIRMWARE_REVISION: UUID = uuid(0x2A26)
  val CCCD: UUID = uuid(0x2902)

  private fun uuid(short: Int): UUID =
      UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))
}

/**
 * A standard chest strap, or any device that exposes the Bluetooth Heart Rate Service. The
 * measurement characteristic's bytes are stored as received under [Codec.BLE_HRS].
 */
@SuppressLint("MissingPermission")
class BleHrsTracker(
    context: Context,
    override val id: String,
    override val name: String,
) : Tracker {
  override val kind = SensorKind.BLE_HRS
  override val streams =
      listOf(
          OfferedStream("hr", StreamType.HEART_RATE, Codec.BLE_HRS, "hr.log", order = 0),
          OfferedStream("rr", StreamType.RR_INTERVAL, Codec.BLE_HRS, "hr.log", order = 1),
      )
  override val capabilities = TrackerCapabilities(clock = false, battery = true, firmware = true)

  private val appContext = context.applicationContext
  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
  private val _battery = MutableStateFlow<Int?>(null)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()
  private val _firmware = MutableStateFlow<String?>(null)
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()

  @Volatile private var listener: TrackerListener? = null
  @Volatile private var selected: Set<String> = emptySet()
  @Volatile private var gatt: BluetoothGatt? = null
  @Volatile private var userDisconnect = false

  override fun recordingModes(selected: Set<String>): Set<RecordingMode> =
      if (selected.any { it == "hr" || it == "rr" }) setOf(RecordingMode.STREAM) else emptySet()

  override fun connect() {
    if (_connection.value == ConnectionState.CONNECTED) return
    userDisconnect = false
    _connection.value = ConnectionState.CONNECTING
    val adapter = bluetoothAdapter(appContext)
    if (adapter == null || !adapter.isEnabled) {
      _connection.value = ConnectionState.DISCONNECTED
      return
    }
    try {
      val connected = adapter.getRemoteDevice(id).connectGatt(appContext, false, callback)
      if (connected == null) {
        _connection.value = ConnectionState.DISCONNECTED
      } else {
        gatt = connected
      }
    } catch (e: Exception) {
      Log.e(TAG, "connect failed", e)
      _connection.value = ConnectionState.DISCONNECTED
    }
  }

  override fun disconnect() {
    userDisconnect = true
    gatt?.disconnect()
    gatt?.close()
    gatt = null
    _connection.value = ConnectionState.DISCONNECTED
  }

  override fun startStreams(selected: Set<String>, listener: TrackerListener) {
    this.selected = selected
    this.listener = listener
    openMeasurement()
  }

  override fun stopStreams() {
    selected = emptySet()
    listener = null
    setNotifications(false)
  }

  override fun readClock(): Long? = null

  private val callback =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
            listener?.onError("the heart rate strap refused the connection ($status)")
          }
          when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
              _connection.value = ConnectionState.CONNECTED
              gatt.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
              _connection.value = ConnectionState.DISCONNECTED
              if (!userDisconnect) {
                // Keep the same tracker object; a reconnect restarts what was selected.
                _connection.value = ConnectionState.CONNECTING
              }
            }
          }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          val service = gatt.getService(Hrs.SERVICE)
          if (service == null) {
            listener?.onError("the device has no heart rate service")
            return
          }
          gatt.getService(Hrs.BATTERY_SERVICE)?.getCharacteristic(Hrs.BATTERY_LEVEL)?.let {
            if (gatt.readCharacteristic(it)) Unit
          }
          gatt
              .getService(Hrs.DEVICE_INFORMATION)
              ?.getCharacteristic(Hrs.FIRMWARE_REVISION)
              ?.let { gatt.readCharacteristic(it) }
          openMeasurement()
        }

        @Deprecated("The API before 33 reports the value here.")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
          if (Build.VERSION.SDK_INT < 33) {
            characteristic.value?.let { onMeasurement(it) }
          }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
          if (characteristic.uuid == Hrs.MEASUREMENT) onMeasurement(value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
          when (characteristic.uuid) {
            Hrs.BATTERY_LEVEL -> if (value.isNotEmpty()) _battery.value = value[0].toInt() and 0xFF
            Hrs.FIRMWARE_REVISION -> _firmware.value = value.toString(Charsets.UTF_8).trim()
          }
        }

        @Deprecated("The API before 33 reports the value here.")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
          if (Build.VERSION.SDK_INT < 33) {
            characteristic.value?.let {
              when (characteristic.uuid) {
                Hrs.BATTERY_LEVEL -> if (it.isNotEmpty()) _battery.value = it[0].toInt() and 0xFF
                Hrs.FIRMWARE_REVISION -> _firmware.value = it.toString(Charsets.UTF_8).trim()
              }
            }
          }
        }
      }

  private fun onMeasurement(value: ByteArray) {
    val l = listener ?: return
    if (selected.none { it == "hr" || it == "rr" }) return
    val stamp = Stamp.now(SystemClockSource)
    val enc = BleHrsCodec.encode(stamp.phoneNs, stamp.elapsedNs, value)
    l.onFrame(TrackerFrame("hr", stamp, enc.bytes))
  }

  private fun openMeasurement() {
    val g = gatt ?: return
    if (selected.none { it == "hr" || it == "rr" }) return
    setNotifications(true)
  }

  private fun setNotifications(enable: Boolean) {
    val g = gatt ?: return
    val characteristic = g.getService(Hrs.SERVICE)?.getCharacteristic(Hrs.MEASUREMENT) ?: return
    runCatching {
      g.setCharacteristicNotification(characteristic, enable)
      characteristic.getDescriptor(Hrs.CCCD)?.let { descriptor ->
        descriptor.value =
            if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        g.writeDescriptor(descriptor)
      }
    }
  }

  private companion object {
    const val TAG = "BleHrsTracker"

    fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
  }
}

/** Matches a standard Heart Rate Service device and creates [BleHrsTracker]. */
class BleHrsDriver : TrackerDriver {
  override val kind = SensorKind.BLE_HRS

  override fun matches(device: FoundSensor): Boolean = device.kind == SensorKind.BLE_HRS

  override fun create(context: Context, device: FoundSensor): Tracker =
      BleHrsTracker(context, device.id, device.name)
}

/**
 * Devices that advertise the Heart Rate Service. Polar straps are left to the Polar drivers, which
 * also know their own services.
 */
@SuppressLint("MissingPermission")
fun scanHeartRateService(context: Context): Flow<FoundSensor> = callbackFlow {
  val adapter =
      context.getSystemService(BluetoothManager::class.java)?.adapter
          ?: run {
            close()
            return@callbackFlow
          }
  if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) ||
      !adapter.isEnabled) {
    close()
    return@callbackFlow
  }
  val scanner = adapter.bluetoothLeScanner ?: run {
    close()
    return@callbackFlow
  }
  val filters =
      listOf(
          ScanFilter.Builder()
              .setServiceUuid(ParcelUuid(Hrs.SERVICE))
              .build()
      )
  val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
  val callback =
      object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
          val device = result.device ?: return
          val name = runCatching { device.name }.getOrNull() ?: device.address ?: return
          // A Polar device is offered by its own driver, with its extra streams.
          if (name.contains("Polar", ignoreCase = true)) return
          val address = device.address ?: return
          trySend(FoundSensor(SensorKind.BLE_HRS, address, name, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
          close()
        }
      }
  scanner.startScan(filters, settings, callback)
  awaitClose { runCatching { scanner.stopScan(callback) } }
}
