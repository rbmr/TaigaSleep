package com.rbmr.taigasleep.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.TrackerRegistry
import com.rbmr.taigasleep.tracker.hrs.scanHeartRateService
import com.rbmr.taigasleep.tracker.polar.scanPolar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one tracker the app talks to, shared by the Home screen and the recording service, plus device
 * scanning. The app container owns the one instance so a service restarted by the OS can pick the
 * tracker up again from the recording's meta.json. Every device comes from a driver in
 * [TrackerRegistry]; nothing here names a strap.
 */
class SensorHub(private val registry: TrackerRegistry = TrackerRegistry.default()) {
  private val _sensor = MutableStateFlow<Tracker?>(null)
  val sensor: StateFlow<Tracker?> = _sensor.asStateFlow()

  private val _found = MutableStateFlow<List<FoundSensor>>(emptyList())
  val found: StateFlow<List<FoundSensor>> = _found.asStateFlow()

  private val _scanning = MutableStateFlow(false)
  val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

  private var scan: Job? = null
  private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val main = Handler(Looper.getMainLooper())
  private val stopScanRunnable = Runnable { stopScan() }

  // The main thread and the recording control thread both obtain and drop trackers; one of them at
  // a time keeps two trackers for one id, and the loser's threads, from being created.
  private val lock = Any()

  /** Scans for every tracker a driver knows for [SCAN_MS]. Needs the Bluetooth permissions. */
  fun startScan(context: Context) {
    stopScan()
    // Debug builds list the fake tracker next to the real ones, so what is selected is always
    // what the Home screen says.
    _found.value = listOfNotNull(DebugTools.fakeTracker)
    _scanning.value = true
    scan =
        scanScope.launch {
          try {
            merge(scanPolar(context), scanHeartRateService(context))
                .catch { _scanning.value = false }
                .collect { s ->
                  if (_found.value.none { it.id == s.id }) {
                    _found.update { list -> list + s }
                  }
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

  /** Makes [found] the current tracker, replacing (and disconnecting) the previous one. */
  fun select(context: Context, found: FoundSensor) {
    stopScan()
    RecordingPrefs.get(context).lastSensor = SavedSensor(found.kind, found.id, found.name)
    obtain(context, found.kind, found.id, found.name)
  }

  /** Re-creates the remembered tracker after a restart of the app. */
  fun restoreLast(context: Context, canConnect: Boolean) {
    if (_sensor.value != null) return
    val prefs = RecordingPrefs.get(context)
    val saved = prefs.lastSensor ?: return
    if (saved.kind == SensorKind.SIMULATED) {
      // The fake tracker only exists in debug builds and needs no permissions to connect.
      if (DebugTools.fakeTracker != null) obtain(context, saved.kind, saved.id, saved.name)
      return
    }
    if (!canConnect) return
    obtain(context, saved.kind, saved.id, saved.name)
  }

  /** Returns the current tracker if it is this one, otherwise creates it and starts connecting. */
  fun obtain(context: Context, kind: SensorKind, id: String, name: String): Tracker =
      synchronized(lock) {
        val current = _sensor.value
        if (current != null && current.kind == kind && current.id == id) return current
        dropCurrent()
        val sensor =
            if (kind == SensorKind.SIMULATED) {
              checkNotNull(DebugTools.createFakeTracker()) {
                "the fake tracker is a debug-build feature"
              }
            } else {
              registry.create(context, FoundSensor(kind, id, name, 0))
            }
        _sensor.value = sensor
        sensor.connect()
        sensor
      }

  /** Disconnects, releases and forgets the current tracker. */
  fun clear() {
    synchronized(lock) { dropCurrent() }
  }

  private fun dropCurrent() {
    val old = _sensor.value ?: return
    _sensor.value = null
    old.stopStreams()
    old.disconnect()
    old.release()
  }

  private companion object {
    const val SCAN_MS = 15_000L
  }
}
