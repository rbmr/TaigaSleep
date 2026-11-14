package com.rbmr.taigasleep.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.debug.GuidancePreviewPicker
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.tracker.ConnectionState

/** What the inactive stage draws, whichever step of the start flow it is on. */
internal data class InactiveView(
    val tracker: TrackerView?,
    val config: StartConfig?,
    val guard: HomeState,
    val denied: Boolean,
    val error: String?,
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
      is HomeUiState.ReadyToStart -> InactiveView(tracker, config, guard, picker.denied, error)
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
  val guidanceStore = remember { GuidanceStore(context) }
  val autoUpload by viewModel.autoUpload.collectAsStateWithLifecycle(initialValue = false)

  var confirm by remember { mutableStateOf<StopAction?>(null) }
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
        ActiveHome(
            state = current,
            onStop = { confirm = StopAction.STOP },
            onForceStop = { confirm = StopAction.FORCE_STOP },
            onDiscard = { confirm = StopAction.DISCARD },
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
        onHr = viewModel::setHr,
        onEcg = viewModel::setEcg,
        onAcc = viewModel::setAcc,
        onMode = viewModel::setMode,
        onWake = viewModel::setWake,
        onDismiss = viewModel::closeConfiguration,
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
  confirm?.let { action ->
    StopConfirmDialog(
        action = action,
        onDevice = (state as? HomeUiState.Recording)?.onDevice == true,
        autoUpload = autoUpload,
        onDismiss = { confirm = null },
        onConfirm = {
          confirm = null
          when (action) {
            StopAction.STOP -> viewModel.stop()
            StopAction.FORCE_STOP -> viewModel.forceStop()
            StopAction.DISCARD -> viewModel.discard()
          }
        },
    )
  }
}
