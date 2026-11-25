package com.rbmr.taigasleep.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.debug.GuidancePreviewPicker
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.recording.WakeAlarmWarning
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.ui.components.ConfirmDialog

/** What the inactive stage draws, whichever step of the start flow it is on. */
internal data class InactiveView(
    val tracker: TrackerView?,
    val config: StartConfig?,
    val guard: HomeState,
    val denied: Boolean,
    val error: String?,
    val wakeWarnings: Set<WakeAlarmWarning> = emptySet(),
    /** A start asked for the microphone and was refused, so Sound is off now. */
    val microphoneDenied: Boolean = false,
)

private fun HomeUiState.inactive(): InactiveView? =
    when (this) {
      is HomeUiState.Recording -> null
      is HomeUiState.Finishing -> null
      is HomeUiState.FetchPending -> null
      is HomeUiState.NoTracker -> InactiveView(null, null, guard, picker.denied, error)
      is HomeUiState.Connecting -> InactiveView(tracker, null, guard, picker.denied, error)
      is HomeUiState.Configure -> InactiveView(tracker, config, guard, picker.denied, error)
      is HomeUiState.NeedsGuard -> InactiveView(tracker, config, guard, picker.denied, error)
      is HomeUiState.ReadyToStart ->
          InactiveView(
              tracker,
              config,
              guard,
              picker.denied,
              error,
              wakeWarnings,
              microphone.denied,
          )
    }

/** The connected tracker whose picker sheet can offer Disconnect, or null while there is none. */
private fun HomeUiState.connectedTracker(): TrackerView? {
  val view =
      when (this) {
        is HomeUiState.Connecting -> tracker
        is HomeUiState.Configure -> tracker
        is HomeUiState.NeedsGuard -> tracker
        is HomeUiState.ReadyToStart -> tracker
        is HomeUiState.NoTracker,
        is HomeUiState.Recording,
        is HomeUiState.Finishing,
        is HomeUiState.FetchPending -> null
      }
  return view?.takeIf { it.connection == ConnectionState.CONNECTED }
}

/**
 * Home and its start flow: the screen draws [HomeUiState] and forwards every action to
 * [HomeViewModel]. The tracker picker, the configuration sheet and the three stages are the files
 * around this one.
 */
@Composable
fun HomeScreen(onGuidanceClick: () -> Unit, onOpenRecording: (String) -> Unit) {
  val context = LocalContext.current
  val factory = remember { HomeFactory(context) }
  val viewModel: HomeViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()
  val ringing by viewModel.ringing.collectAsStateWithLifecycle()
  val guidanceStore = context.appContainer.guidanceStore
  val autoUpload by viewModel.autoUpload.collectAsStateWithLifecycle(initialValue = false)

  var showPreviewPicker by remember { mutableStateOf(false) }
  // The debug-only preview of another OS, honoured by the card exactly as the flow honours it.
  val readinessLongClick: (() -> Unit)? =
      if (DebugTools.GUIDANCE_PREVIEW) ({ showPreviewPicker = true }) else null

  // On first show: mark recordings a dead process left behind, and reconnect the remembered sensor.
  LaunchedEffect(Unit) { viewModel.onFirstShow() }
  // The user may have changed the battery settings in the system UI or in the flow.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshGuard() }

  val scanPermissions =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.bluetoothAnswered(hasBluetooth(context))
      }
  LaunchedEffect(state.picker.permissionRequested) {
    if (state.picker.permissionRequested) {
      viewModel.permissionShown()
      scanPermissions.launch(bluetoothPermissions)
    }
  }
  val notificationPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The recording runs without the notification, so a refusal does not block the start.
        viewModel.start()
      }
  // The microphone is asked for when the user turns Sound on, and again at a start with sound
  // when Android no longer grants it. A refusal keeps sound off.
  val microphonePermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val activity = context.findActivity()
        val canAskAgain =
            activity == null ||
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO,
                )
        viewModel.microphoneAnswered(it, canAskAgain)
      }
  val microphoneAsked =
      when (val current = state) {
        is HomeUiState.Configure -> current.microphone.requested
        is HomeUiState.ReadyToStart -> current.microphone.requested
        else -> false
      }
  LaunchedEffect(microphoneAsked) {
    if (microphoneAsked) {
      viewModel.microphoneShown()
      microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun start() {
    val needsNotification =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    if (needsNotification) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      viewModel.start()
    }
  }

  // Two separate stages: nothing from one is shown on the other.
  when (val current = state) {
    is HomeUiState.Recording ->
        RecordingHome(
            state = current,
            autoUpload = autoUpload,
            onStop = viewModel::stop,
            onForceStop = viewModel::forceStop,
            onDiscard = viewModel::discard,
            onWake = viewModel::openLiveWake,
            ringing = ringing,
            onStopAlarm = viewModel::stopAlarm,
        )
    is HomeUiState.Finishing ->
        FinishingHome(
            state = current,
            onOpenRecording = {
              // Opening the recording is what the done card is for; Home goes back to its start flow.
              viewModel.dismissFinishing()
              onOpenRecording(current.finishing.recordingId)
            },
            onDismiss = viewModel::dismissFinishing,
        )
    is HomeUiState.FetchPending ->
        FetchPendingHome(state = current, onFetchAgain = viewModel::fetchAgain)
    else ->
        current.inactive()?.let { view ->
          IdleHome(
              view = view,
              onSelectTracker = viewModel::selectSensor,
              onDataSettings = viewModel::openConfiguration,
              onGuard = onGuidanceClick,
              onGuardLongClick = readinessLongClick,
              onStart = ::start,
              ringing = ringing,
              onStopAlarm = viewModel::stopAlarm,
          )
        }
  }

  if (state.picker.visible) {
    SensorPickerSheet(
        picker = state.picker,
        connected = state.connectedTracker(),
        onSelect = viewModel::choose,
        onScanAgain = viewModel::scanAgain,
        onDisconnect = {
          viewModel.disconnect()
          viewModel.closeScanner()
        },
        onDismiss = viewModel::closeScanner,
    )
  }
  (state as? HomeUiState.Configure)?.let { configured ->
    ConfigureSheet(
        config = configured.config,
        onStream = viewModel::setStream,
        onMode = viewModel::setMode,
        onWake = viewModel::setWake,
        onSound = viewModel::setSound,
        wakeWarnings = configured.wakeWarnings,
        alarmTest = configured.alarmTest,
        onTestAlarm = viewModel::testAlarm,
        onDismiss = viewModel::closeConfiguration,
        microphone = configured.microphone,
    )
  }
  (state as? HomeUiState.Recording)?.takeIf { it.editingWake }?.let { recording ->
    recording.wake?.let { wake ->
      LiveWakeSheet(
          wake = wake,
          wakeWarnings = recording.wakeWarnings,
          alarmTest = recording.alarmTest,
          onWake = viewModel::setLiveWake,
          onTestAlarm = viewModel::testAlarm,
          onDismiss = viewModel::closeLiveWake,
      )
    }
  }
  val microphoneBlocked =
      when (val current = state) {
        is HomeUiState.Configure -> current.microphone.blocked
        is HomeUiState.ReadyToStart -> current.microphone.blocked
        else -> false
      }
  if (microphoneBlocked) {
    MicrophoneSettingsDialog(
        onOpenSettings = {
          viewModel.openingMicrophoneSettings()
          context.startActivity(
              Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.fromParts("package", context.packageName, null),
              )
          )
        },
        onDismiss = viewModel::microphoneSettingsDeclined,
    )
  }
  if (showPreviewPicker) {
    GuidancePreviewPicker(
        store = guidanceStore,
        onDismiss = {
          showPreviewPicker = false
          viewModel.refreshGuard()
        },
    )
  }
}

/** Shown when Android refuses the microphone without asking: only the app's settings can allow it. */
@Composable
internal fun MicrophoneSettingsDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
  ConfirmDialog(
      title = stringResource(R.string.rec_sound_blocked_title),
      body = stringResource(R.string.rec_sound_blocked_body),
      confirmLabel = stringResource(R.string.rec_sound_blocked_open),
      onConfirm = onOpenSettings,
      onDismiss = onDismiss,
  )
}

private fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()

