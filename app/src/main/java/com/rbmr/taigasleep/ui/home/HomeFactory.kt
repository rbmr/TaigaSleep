package com.rbmr.taigasleep.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.guard.GuidanceFlows
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.guard.ReadinessState
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.SleepSensor
import com.rbmr.taigasleep.tracker.StreamConfig

/** Bluetooth is needed to see and reach the straps. */
internal val bluetoothPermissions =
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

internal fun hasBluetooth(context: Context): Boolean =
    bluetoothPermissions.all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Builds the Home screen's view model from the singletons the app already has. The tracker, the
 * recorder and the readiness check are reached only from here.
 */
class HomeFactory(private val context: Context) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val app = context.applicationContext
    val sensors = app.appContainer.sensorHub
    val prefs = RecordingPrefs.get(app)
    val guidance = GuidanceStore(app)
    val work =
        object : HomeWork {
          override val found = sensors.found
          override val scanning = sensors.scanning

          override fun onFirstShow() {
            RecordingRepository.reconcile(app)
            sensors.restoreLast(app, canConnect = hasBluetooth(app))
          }

          override fun hasBluetooth(): Boolean = hasBluetooth(app)

          override fun restoreLast(canConnect: Boolean) {
            sensors.restoreLast(app, canConnect)
          }

          override fun savedConfig(sensor: SleepSensor): NightConfig = configOf(sensor, prefs)

          override fun setEcg(sensorId: String, on: Boolean) {
            prefs.setEcgEnabled(sensorId, on)
          }

          override fun setAcc(sensorId: String, on: Boolean) {
            prefs.setAccEnabled(sensorId, on)
          }

          override fun setMode(sensorId: String, mode: RecordingMode) {
            prefs.setMode(sensorId, mode)
          }

          override fun guard(mode: RecordingMode): HomeState =
              ReadinessState.state(app, guidance, GuidanceFlows.previewOf(guidance), mode)

          override fun start(config: NightConfig) {
            val sensor = sensors.sensor.value ?: return
            RecordingRepository.setIdle()
            val streams = StreamConfig(ecg = config.streams.ecg, acc = config.streams.acc)
            RecordingService.start(app, sensor, streams, config.mode)
          }

          override fun stop() {
            RecordingService.send(app, RecordingService.ACTION_STOP)
          }

          override fun forceStop() {
            RecordingService.send(app, RecordingService.ACTION_FORCE_STOP)
          }

          override fun discard() {
            RecordingService.send(app, RecordingService.ACTION_DISCARD)
          }

          override fun disconnect() {
            sensors.clear()
            prefs.lastSensor = null
          }

          override fun startScan() {
            sensors.startScan(app)
          }

          override fun stopScan() {
            sensors.stopScan()
          }

          override fun select(found: FoundSensor) {
            sensors.select(app, found)
          }
        }
    return HomeViewModel(work, sensors.sensor, RecordingRepository.status) as T
  }

  /** The configuration step: the remembered streams, the modes they allow and the chosen mode. */
  private fun configOf(sensor: SleepSensor, prefs: RecordingPrefs): NightConfig {
    val streams =
        Streams(
            ecg = sensor.capabilities.ecg && prefs.ecgEnabled(sensor.id),
            acc = sensor.capabilities.acc && prefs.accEnabled(sensor.id),
        )
    val modes = sensor.recordingModes(StreamConfig(ecg = streams.ecg, acc = streams.acc))
    val saved = prefs.mode(sensor.id)
    val mode =
        when {
          saved in modes -> saved
          RecordingMode.STREAM in modes -> RecordingMode.STREAM
          else -> modes.firstOrNull() ?: RecordingMode.STREAM
        }
    return NightConfig(
        streams = streams,
        modes = modes,
        mode = mode,
        canRecordEcg = sensor.capabilities.ecg,
        canRecordAcc = sensor.capabilities.acc,
    )
  }
}
