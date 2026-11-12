package com.rbmr.taigasleep.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.polar.PolarH10Sensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one sensor the app talks to, shared by the Home screen and the recording service, plus device
 * scanning. The app container owns the one instance so a service restarted by the OS can pick the
 * sensor up again from the recording's meta.json.
 */
class SensorHub {
  private val _sensor = MutableStateFlow<SleepSensor?>(null)
  val sensor: StateFlow<SleepSensor?> = _sensor.asStateFlow()

  private val _found = MutableStateFlow<List<FoundSensor>>(emptyList())
  val found: StateFlow<List<FoundSensor>> = _found.asStateFlow()

  private val _scanning = MutableStateFlow(false)
  val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

  private var scan: Job? = null
  private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val main = Handler(Looper.getMainLooper())
  private val stopScanRunnable = Runnable { stopScan() }

  /** Scans for H10 straps for [SCAN_MS]. Needs the Bluetooth permissions. */
  fun startScan(context: Context) {
    stopScan()
    // Debug builds list the simulated sensor next to the real ones, so what is selected is always
    // what the Home screen says.
    _found.value = listOfNotNull(DebugTools.simulatedSensor)
    _scanning.value = true
    scan =
        scanScope.launch {
          try {
            PolarH10Sensor.scan(context)
                // Only the H10 is supported so far; Verity and Loop would be listed here later.
                .filter { it.name.contains("H10", ignoreCase = true) }
                .catch { _scanning.value = false }
                .collect { d ->
                  val s = FoundSensor(SensorKind.POLAR_H10, d.deviceId, d.name, d.rssi)
                  _found.update { list -> list.filter { it.id != s.id } + s }
                }
          } catch (_: SecurityException) {
            _scanning.value = false
          }
        }
    main.postDelayed(stopScanRunnable, SCAN_MS)
  }

  fun stopScan() {
    main.removeCallbacks(stopScanRunnable)
    scan?.cancel()
    scan = null
    _scanning.value = false
  }

  /** Makes [found] the current sensor, replacing (and disconnecting) the previous one. */
  fun select(context: Context, found: FoundSensor) {
    stopScan()
    RecordingPrefs.get(context).lastSensor = SavedSensor(found.kind, found.id, found.name)
    obtain(context, found.kind, found.id, found.name)
  }

  /** Re-creates the remembered sensor after a restart of the app. */
  fun restoreLast(context: Context, canConnect: Boolean) {
    if (_sensor.value != null) return
    val prefs = RecordingPrefs.get(context)
    val saved = prefs.lastSensor ?: return
    if (saved.kind == SensorKind.SIMULATED) {
      // The simulated sensor only exists in debug builds and needs no permissions to connect.
      if (DebugTools.simulatedSensor != null) obtain(context, saved.kind, saved.id, saved.name)
      return
    }
    if (!canConnect) return
    obtain(context, saved.kind, saved.id, saved.name)
  }

  /** Returns the current sensor if it is this one, otherwise creates it and starts connecting. */
  fun obtain(context: Context, kind: SensorKind, id: String, name: String): SleepSensor {
    _sensor.value?.let { if (it.kind == kind && it.id == id) return it }
    clear()
    val sensor =
        when (kind) {
          SensorKind.POLAR_H10 -> PolarH10Sensor(context, id, name)
          SensorKind.SIMULATED ->
              checkNotNull(DebugTools.createSimulatedSensor()) {
                "simulated sensor is a debug-build feature"
              }
        }
    _sensor.value = sensor
    sensor.connect()
    return sensor
  }

  /** Disconnects and forgets the current sensor. */
  fun clear() {
    val old = _sensor.getAndSet(null) ?: return
    old.stopStreams()
    old.disconnect()
    (old as? PolarH10Sensor)?.release()
  }

  private fun <T> MutableStateFlow<T>.getAndSet(v: T): T = value.also { value = v }

  private companion object {
    const val SCAN_MS = 15_000L
  }
}
