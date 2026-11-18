package com.rbmr.taigasleep.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.guard.GuidanceFlows
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.guard.ReadinessState
import com.rbmr.taigasleep.recording.AlarmTestResult
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.recording.WakeAlarm
import com.rbmr.taigasleep.recording.WakeAlarmWarning
import com.rbmr.taigasleep.recording.WakeWindow
import com.rbmr.taigasleep.recording.readWakeAlarmAudibility
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.RecordingMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
private val OPT_IN_STREAMS = setOf("ppg", "pulse_interval", "gyroscope", "magnetometer")

class HomeFactory(private val context: Context) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val app = context.applicationContext
    val sensors = app.appContainer.sensorHub
    val prefs = app.appContainer.recordingPrefs
    val guidance = app.appContainer.guidanceStore
    val work =
        object : HomeWork {
          override val found = sensors.found
          override val scanning = sensors.scanning

          override fun onFirstShow() {
            app.appContainer.scope.launch { RecordingRepository.reconcile(app) }
            sensors.restoreLast(app, canConnect = hasBluetooth(app))
          }

          override fun hasBluetooth(): Boolean = hasBluetooth(app)

          override fun restoreLast(canConnect: Boolean) {
            sensors.restoreLast(app, canConnect)
          }

          override fun savedConfig(sensor: Tracker): StartConfig = configOf(sensor, prefs)

          override fun setStream(sensorId: String, streamId: String, on: Boolean) {
            prefs.setStreamEnabled(sensorId, streamId, on)
          }

          override fun setMode(sensorId: String, mode: RecordingMode) {
            prefs.setMode(sensorId, mode)
          }

          override fun setWake(sensorId: String, wake: WakeChoice?) {
            prefs.setWakeWindow(
                sensorId,
                wake?.let {
                  WakeWindow(it.startMinute, it.lengthMinutes, it.heartRate, it.movement)
                },
            )
          }

          override fun changeLiveWake(sensorId: String?, wake: WakeChoice?) {
            val window =
                wake?.let { WakeWindow(it.startMinute, it.lengthMinutes, it.heartRate, it.movement) }
            if (sensorId != null) prefs.setWakeWindow(sensorId, window)
            RecordingService.changeWake(app, window)
          }

          override fun guard(mode: RecordingMode): HomeState =
              ReadinessState.state(app, guidance, GuidanceFlows.previewOf(guidance), mode)

          override fun wakeWarnings(): Set<WakeAlarmWarning> =
              readWakeAlarmAudibility(app).warnings

          override val alarmTest: StateFlow<AlarmTestResult?> = WakeAlarm.testResult

          override val ringing: StateFlow<Boolean> = WakeAlarm.ringing

          override fun stopAlarm() {
            WakeAlarm.stopNow(app)
          }

          override fun testAlarm() {
            WakeAlarm.test(app)
          }

          override fun start(config: StartConfig) {
            val sensor = sensors.sensor.value ?: return
            RecordingRepository.setIdle()
            RecordingService.start(app, sensor, config.streams.chosen, config.mode)
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

          override fun fetchAgain(recordingId: String) {
            RecordingService.fetch(app, recordingId)
          }

          override fun dismissFinishing() {
            RecordingRepository.dismissFinishing()
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
    return HomeViewModel(
        work,
        sensors.sensor,
        RecordingRepository.status,
        app.appContainer.uploadSettings.autoUpload.map { it.enabled },
    ) as T
  }

  /** The configuration step: the stored choices, resolved for the stored mode. */
  private fun configOf(sensor: Tracker, prefs: RecordingPrefs): StartConfig {
    val chosen =
        sensor.streams
            .filter {
              prefs.streamEnabled(
                  sensor.id,
                  it.id,
                  default = it.type.wire !in OPT_IN_STREAMS,
              )
            }
            .map { it.id }
            .toSet()
    return StartConfig.resolve(
        saved =
            SavedChoices(
                chosen = chosen,
                mode = prefs.mode(sensor.id),
                wake =
                    prefs.wakeWindow(sensor.id)?.let {
                      WakeChoice(it.startMinute, it.lengthMinutes, it.heartRate, it.movement)
                    },
            ),
        offered = sensor.streams.sortedBy { it.order }.map { StreamOption(it.id, it.type) },
        modes = sensor.recordingModes(chosen),
    )
  }
}
