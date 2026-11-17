@file:Suppress("ReturnCount", "TooGenericExceptionCaught", "NestedBlockDepth", "TooManyFunctions")

package com.rbmr.taigasleep.tracker.hrs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.os.HandlerCompat
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

/** Schedules the tracker's reconnect attempts; a test runs them by hand. */
interface RetryScheduler {
  fun schedule(delayMs: Long, action: () -> Unit)

  fun cancel()
}

private class HandlerRetryScheduler : RetryScheduler {
  private val handler = Handler(Looper.getMainLooper())
  private var pending: Runnable? = null

  override fun schedule(delayMs: Long, action: () -> Unit) {
    cancel()
    val runnable = Runnable { action() }
    pending = runnable
    HandlerCompat.postDelayed(handler, runnable, null, delayMs)
  }

  override fun cancel() {
    pending?.let { handler.removeCallbacks(it) }
    pending = null
  }
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
    private val seam: GattSeam = AndroidGattSeam(context.applicationContext),
    private val retry: RetryScheduler = HandlerRetryScheduler(),
) : Tracker {
  override val kind = SensorKind.BLE_HRS
  override val streams =
      listOf(
          OfferedStream("hr", StreamType.HEART_RATE, Codec.BLE_HRS, "hr.log", order = 0),
          OfferedStream("rr", StreamType.RR_INTERVAL, Codec.BLE_HRS, "hr.log", order = 1),
      )
  override val capabilities = TrackerCapabilities(clock = false, battery = true, firmware = true)

  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
  private val _battery = MutableStateFlow<Int?>(null)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()
  private val _firmware = MutableStateFlow<String?>(null)
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()

  @Volatile private var listener: TrackerListener? = null
  @Volatile private var selected: Set<String> = emptySet()
  @Volatile private var gatt: GattHandle? = null
  @Volatile private var userDisconnect = false
  @Volatile private var retryAttempt = 0
  @Volatile private var retryPending = false
  @Volatile private var everConnected = false

  override fun recordingModes(selected: Set<String>): Set<RecordingMode> =
      if (selected.any { it == "hr" || it == "rr" }) setOf(RecordingMode.STREAM) else emptySet()

  override fun connect() {
    if (_connection.value == ConnectionState.CONNECTED) return
    userDisconnect = false
    retry.cancel()
    retryPending = false
    _connection.value = ConnectionState.CONNECTING
    openGatt()
  }

  /** Opens a fresh GATT connection, closing the one that is no longer usable. */
  private fun openGatt() {
    gatt?.let {
      it.disconnect()
      it.close()
    }
    gatt = null
    if (!seam.isEnabled()) {
      _connection.value = ConnectionState.DISCONNECTED
      return
    }
    gatt = seam.connect(id) { event -> onEvent(event) }
    if (gatt == null) {
      _connection.value = ConnectionState.DISCONNECTED
      listener?.onError("the heart rate strap could not be opened")
    }
  }

  override fun disconnect() {
    userDisconnect = true
    retry.cancel()
    retryPending = false
    gatt?.disconnect()
    gatt?.close()
    gatt = null
    _connection.value = ConnectionState.DISCONNECTED
  }

  override fun startStreams(selected: Set<String>, listener: TrackerListener) {
    this.selected = selected
    this.listener = listener
    if (_connection.value == ConnectionState.CONNECTED) {
      openMeasurement()
    } else {
      connect()
    }
  }

  override fun stopStreams() {
    selected = emptySet()
    listener = null
    retry.cancel()
    retryPending = false
    setNotifications(false)
  }

  override fun readClock(): Long? = null

  private fun onEvent(event: GattEvent) {
    if (event.handle !== gatt) return
    when (event) {
      is GattEvent.Connected -> {
        _connection.value = ConnectionState.CONNECTED
        retryAttempt = 0
        if (everConnected) listener?.onNote("reconnect", "heart rate strap reconnected")
        everConnected = true
        if (!event.handle.discoverServices()) {
          listener?.onError("the heart rate strap did not start service discovery")
          lost(event.handle)
        }
      }
      is GattEvent.Disconnected -> {
        if (event.status != 0) {
          listener?.onError("the heart rate strap refused the connection (${event.status})")
        }
        lost(event.handle)
      }
      is GattEvent.ServicesDiscovered -> {
        if (event.status != 0 || !event.handle.hasService(Hrs.SERVICE)) {
          listener?.onError("the heart rate strap has no heart rate service")
          lost(event.handle)
          return
        }
        readBatteryAndFirmware(event.handle)
        openMeasurement()
      }
      is GattEvent.CharacteristicRead -> onCharacteristicRead(event)
      is GattEvent.DescriptorWritten -> Unit
      is GattEvent.CharacteristicChanged -> {
        if (event.characteristic == Hrs.MEASUREMENT) onMeasurement(event.value)
      }
    }
  }

  /** A dropped link is closed, noted and retried with a bounded backoff. */
  private fun lost(handle: GattHandle) {
    if (handle !== gatt) return
    handle.close()
    gatt = null
    if (userDisconnect) {
      _connection.value = ConnectionState.DISCONNECTED
      return
    }
    _connection.value = ConnectionState.CONNECTING
    listener?.onNote("disconnect", "heart rate strap disconnected")
    scheduleRetry()
  }

  private fun scheduleRetry() {
    if (userDisconnect || retryPending) return
    val delay =
        when (retryAttempt) {
          0 -> 1_000L
          1 -> 2_000L
          2 -> 4_000L
          3 -> 8_000L
          4 -> 16_000L
          else -> 30_000L
        }
    retryAttempt++
    retryPending = true
    retry.schedule(delay) {
      retryPending = false
      if (userDisconnect || _connection.value != ConnectionState.CONNECTING) return@schedule
      listener?.onNote("reconnect", "trying the heart rate strap again")
      openGatt()
    }
  }

  private fun onCharacteristicRead(event: GattEvent.CharacteristicRead) {
    when (event.characteristic) {
      Hrs.BATTERY_LEVEL ->
          if (event.status == 0 && event.value.isNotEmpty()) {
            _battery.value = event.value[0].toInt() and 0xFF
          }
      Hrs.FIRMWARE_REVISION ->
          if (event.status == 0) {
            _firmware.value = event.value.toString(Charsets.UTF_8).trim()
          }
    }
  }

  private fun readBatteryAndFirmware(handle: GattHandle) {
    if (handle.hasCharacteristic(Hrs.BATTERY_SERVICE, Hrs.BATTERY_LEVEL)) {
      handle.readCharacteristic(Hrs.BATTERY_SERVICE, Hrs.BATTERY_LEVEL)
    }
    if (handle.hasCharacteristic(Hrs.DEVICE_INFORMATION, Hrs.FIRMWARE_REVISION)) {
      handle.readCharacteristic(Hrs.DEVICE_INFORMATION, Hrs.FIRMWARE_REVISION)
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
    if (selected.none { it == "hr" || it == "rr" }) return
    setNotifications(true)
  }

  private fun setNotifications(enable: Boolean) {
    val handle = gatt ?: return
    val enabled =
        runCatching {
              handle.setNotifications(Hrs.SERVICE, Hrs.MEASUREMENT, Hrs.CCCD, enable)
            }
            .getOrDefault(false)
    if (enable && !enabled) listener?.onError("could not enable heart rate notifications")
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
