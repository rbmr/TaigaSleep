package com.rbmr.taigasleep.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.debug.GuidancePreviewPicker
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.TaigaLogo

/** What the inactive stage draws, whichever step of the start flow it is on. */
private data class InactiveView(
    val tracker: TrackerView?,
    val config: StartConfig?,
    val guard: HomeState,
    val canStart: Boolean,
    val denied: Boolean,
    val error: String?,
)

private fun HomeUiState.inactive(): InactiveView? =
    when (this) {
      is HomeUiState.Recording -> null
      is HomeUiState.FetchPending -> null
      is HomeUiState.NoTracker ->
          InactiveView(null, null, guard, canStart = false, picker.denied, error)
      is HomeUiState.Connecting ->
          InactiveView(tracker, null, guard, canStart = false, picker.denied, error)
      is HomeUiState.Configure ->
          InactiveView(
              tracker,
              config,
              guard,
              canStart = guard != HomeState.NEEDS_SETTINGS,
              picker.denied,
              error,
          )
      is HomeUiState.NeedsGuard ->
          InactiveView(tracker, config, guard, canStart = false, picker.denied, error)
      is HomeUiState.ReadyToStart ->
          InactiveView(tracker, config, guard, canStart = true, picker.denied, error)
    }

/**
 * Home and its start flow: the screen draws [HomeUiState] and forwards every action to
 * [HomeViewModel]. The sensor picker, the configuration sheet, the recording card and the guard
 * card are the files around this one.
 */
@Composable
fun HomeScreen(onGuidanceClick: () -> Unit) {
  val context = LocalContext.current
  val factory = remember { HomeFactory(context) }
  val viewModel: HomeViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()
  val guidanceStore = remember { GuidanceStore(context) }

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

  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(
                  Brush.verticalGradient(
                      colors =
                          listOf(
                              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                              MaterialTheme.colorScheme.background,
                          ),
                      endY = 700f,
                  )
              )
  ) {
    // Two separate stages: nothing from one is shown on the other.
    when (val current = state) {
      is HomeUiState.Recording ->
          ActiveHome(
              state = current,
              onStop = { confirm = StopAction.STOP },
              onForceStop = { confirm = StopAction.FORCE_STOP },
              onDiscard = { confirm = StopAction.DISCARD },
          )
      is HomeUiState.FetchPending ->
          FetchPendingHome(state = current, onFetchAgain = viewModel::fetchAgain)
      else ->
          current.inactive()?.let { view ->
            InactiveHome(
                view = view,
                onSelectSensor = viewModel::selectSensor,
                onDisconnect = viewModel::disconnect,
                onDataSettings = viewModel::openConfiguration,
                onReadiness = onGuidanceClick,
                onReadinessLongClick = readinessLongClick,
                onStart = ::start,
            )
          }
    }
  }

  if (state.picker.visible) {
    SensorPickerSheet(
        picker = state.picker,
        onSelect = viewModel::choose,
        onScanAgain = viewModel::scanAgain,
        onDismiss = viewModel::closeScanner,
    )
  }
  (state as? HomeUiState.Configure)?.let { configured ->
    DataSheet(
        config = configured.config,
        onEcg = viewModel::setEcg,
        onAcc = viewModel::setAcc,
        onMode = viewModel::setMode,
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
    ConfirmDialog(
        action = action,
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

/** Not recording: pick the sensor, check readiness and start. */
@Composable
private fun InactiveHome(
    view: InactiveView,
    onSelectSensor: () -> Unit,
    onDisconnect: () -> Unit,
    onDataSettings: () -> Unit,
    onReadiness: () -> Unit,
    onReadinessLongClick: (() -> Unit)?,
    onStart: () -> Unit,
) {
  val connected = view.tracker?.connection == ConnectionState.CONNECTED
  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      ScreenHeader(
          title = stringResource(R.string.rec_home_title_idle),
          subtitle = stringResource(R.string.rec_home_subtitle_idle),
      )
    }
    item {
      HomeLogo(
          if (view.guard == HomeState.READY || view.guard == HomeState.NOT_NEEDED) {
            LogoVariant.Sleeping
          } else {
            LogoVariant.Awake
          },
          glow = false,
      )
    }
    item {
      SensorCard(
          tracker = view.tracker,
          denied = view.denied,
          canConfigure = view.config?.let { it.canRecordEcg || it.canRecordAcc } == true,
          onSelect = onSelectSensor,
          onDisconnect = onDisconnect,
          onDataSettings = onDataSettings,
      )
    }
    item { GuardCard(guard = view.guard, onClick = onReadiness, onLongClick = onReadinessLongClick) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        view.error?.let {
          Text(
              text = it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
          )
        }
        PrimaryButton(
            text = stringResource(R.string.home_start),
            onClick = onStart,
            enabled = view.canStart,
            size = ButtonSize.Large,
        )
        if (!view.canStart) {
          Text(
              text =
                  stringResource(
                      if (!connected) R.string.rec_start_need_sensor
                      else R.string.rec_start_need_ready
                  ),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** The bear under the header: asleep when all is well, awake when something needs fixing. */
@Composable
internal fun HomeLogo(variant: LogoVariant, glow: Boolean) {
  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    TaigaLogo(
        modifier = Modifier.width(if (glow) 170.dp else 190.dp),
        variant = variant,
        glow = glow,
        contentDescription =
            stringResource(
                when {
                  glow -> R.string.home_logo_recording
                  variant == LogoVariant.Awake -> R.string.home_logo_awake
                  else -> R.string.home_logo_sleeping
                }
            ),
    )
  }
}

