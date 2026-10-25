package com.rbmr.taigasleep.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.Phase
import com.rbmr.taigasleep.recording.ReadinessGate
import com.rbmr.taigasleep.recording.RecordingFormat
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.recording.Readiness
import com.rbmr.taigasleep.recording.RecordingState
import com.rbmr.taigasleep.recording.RecordingStatus
import com.rbmr.taigasleep.sensor.ConnectionState
import com.rbmr.taigasleep.sensor.SensorHub
import com.rbmr.taigasleep.sensor.SensorKind
import com.rbmr.taigasleep.sensor.SleepSensor
import com.rbmr.taigasleep.sensor.StreamConfig
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.LogoVariant
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaLogo
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

private val bluetoothPermissions =
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private fun hasBluetooth(context: Context) =
    bluetoothPermissions.all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private enum class StopAction {
  STOP,
  FORCE_STOP,
  DISCARD,
}

@Composable
fun HomeScreen() {
  val context = LocalContext.current
  val status by RecordingRepository.status.collectAsStateWithLifecycle()
  val sensor by SensorHub.sensor.collectAsStateWithLifecycle()
  val prefs = remember { RecordingPrefs.get(context) }

  var readiness by remember { mutableStateOf(ReadinessGate.check(context)) }
  var showScan by remember { mutableStateOf(false) }
  var showData by remember { mutableStateOf(false) }
  var confirm by remember { mutableStateOf<StopAction?>(null) }
  var denied by remember { mutableStateOf(false) }

  // On first show: mark sessions a dead process left behind, and reconnect the remembered sensor.
  LaunchedEffect(Unit) {
    RecordingRepository.reconcile(context)
    SensorHub.restoreLast(context, canConnect = hasBluetooth(context))
  }
  // The user may have changed the battery settings in the system UI.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    readiness = ReadinessGate.check(context)
  }

  val scanPermissions =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        denied = !hasBluetooth(context)
        if (!denied) {
          showScan = true
          SensorHub.restoreLast(context, canConnect = true)
        }
      }
  val notificationPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The recording runs without the notification, so a refusal does not block the start.
        startRecording(context, sensor, prefs)
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
    if (status.phase == Phase.IDLE) {
      InactiveHome(
          sensor = sensor,
          denied = denied,
          readiness = readiness,
          error = status.error,
          onSelectSensor = {
            if (hasBluetooth(context)) showScan = true
            else scanPermissions.launch(bluetoothPermissions)
          },
          onDisconnect = {
            SensorHub.clear()
            prefs.lastSensor = null
          },
          onDataSettings = { showData = true },
          onFixBattery = { context.startActivity(ReadinessGate.batteryOptimizationIntent(context)) },
          onFixSettings = { context.startActivity(ReadinessGate.appSettingsIntent(context)) },
          onStart = {
            val needsNotification =
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
            if (needsNotification) {
              notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
              startRecording(context, sensor, prefs)
            }
          },
      )
    } else {
      ActiveHome(
          status = status,
          onStop = { confirm = StopAction.STOP },
          onForceStop = { confirm = StopAction.FORCE_STOP },
          onDiscard = { confirm = StopAction.DISCARD },
      )
    }
  }

  if (showScan) ScanSheet(onDismiss = { showScan = false })
  if (showData) sensor?.let { DataDialog(it, prefs, onDismiss = { showData = false }) }
  confirm?.let { action ->
    ConfirmDialog(
        action = action,
        onDismiss = { confirm = null },
        onConfirm = {
          confirm = null
          RecordingService.send(
              context,
              when (action) {
                StopAction.STOP -> RecordingService.ACTION_STOP
                StopAction.FORCE_STOP -> RecordingService.ACTION_FORCE_STOP
                StopAction.DISCARD -> RecordingService.ACTION_DISCARD
              },
          )
        },
    )
  }
}

/** Not recording: pick the sensor, check readiness and start. */
@Composable
private fun InactiveHome(
    sensor: SleepSensor?,
    denied: Boolean,
    readiness: Readiness,
    error: String?,
    onSelectSensor: () -> Unit,
    onDisconnect: () -> Unit,
    onDataSettings: () -> Unit,
    onFixBattery: () -> Unit,
    onFixSettings: () -> Unit,
    onStart: () -> Unit,
) {
  val connected = sensor?.connection?.collectAsStateWithLifecycle()?.value
  val canStart = connected == ConnectionState.CONNECTED && readiness.ok
  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      ScreenHeader(
          kicker = stringResource(R.string.home_kicker),
          title = stringResource(R.string.rec_home_title_idle),
          subtitle = stringResource(R.string.rec_home_subtitle_idle),
      )
    }
    item { HomeLogo(if (readiness.ok) LogoVariant.Sleeping else LogoVariant.Awake, glow = false) }
    item {
      SensorCard(
          sensor = sensor,
          denied = denied,
          onSelect = onSelectSensor,
          onDisconnect = onDisconnect,
          onDataSettings = onDataSettings,
      )
    }
    item { ReadinessCard(readiness = readiness, onBattery = onFixBattery, onSettings = onFixSettings) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let {
          Text(
              text = it,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
          )
        }
        PrimaryButton(
            text = stringResource(R.string.home_start),
            onClick = onStart,
            enabled = canStart,
            size = ButtonSize.Large,
        )
        if (!canStart) {
          Text(
              text =
                  stringResource(
                      if (connected != ConnectionState.CONNECTED) R.string.rec_start_need_sensor
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

/** Recording: the live numbers and the three ways to end it. Nothing else. */
@Composable
private fun ActiveHome(
    status: RecordingStatus,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onDiscard: () -> Unit,
) {
  val stopping = status.phase == Phase.STOPPING
  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      ScreenHeader(
          kicker = stringResource(R.string.home_kicker),
          title = stringResource(R.string.rec_home_title_active),
          subtitle = stringResource(R.string.rec_home_subtitle_active),
      )
    }
    item { HomeLogo(LogoVariant.Sleeping, glow = true) }
    item { ActiveCard(status) }
    item {
      PrimaryButton(
          text = stringResource(R.string.rec_stop),
          onClick = onStop,
          enabled = !stopping,
          size = ButtonSize.Large,
      )
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = stringResource(R.string.rec_force_stop),
            onClick = onForceStop,
            enabled = !stopping,
            modifier = Modifier.fillMaxWidth(),
        )
        Note(R.string.rec_force_stop_note)
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DestructiveButton(
            text = stringResource(R.string.rec_discard),
            onClick = onDiscard,
            enabled = !stopping,
            modifier = Modifier.fillMaxWidth(),
        )
        Note(R.string.rec_discard_note)
      }
    }
  }
}

/** The bear under the header: asleep when all is well, awake when something needs fixing. */
@Composable
private fun HomeLogo(variant: LogoVariant, glow: Boolean) {
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

@Composable
private fun Note(res: Int) {
  Text(
      text = stringResource(res),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun ConfirmDialog(action: StopAction, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  val (title, body, label) =
      when (action) {
        StopAction.STOP ->
            Triple(R.string.rec_stop_title, R.string.rec_stop_body, R.string.rec_stop)
        StopAction.FORCE_STOP ->
            Triple(R.string.rec_force_stop_title, R.string.rec_force_stop_note, R.string.rec_force_stop)
        StopAction.DISCARD ->
            Triple(R.string.rec_discard_title, R.string.rec_discard_body, R.string.rec_discard_confirm)
      }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(title)) },
      text = { Text(stringResource(body)) },
      confirmButton = {
        TextButton(onClick = onConfirm) {
          Text(
              text = stringResource(label),
              color =
                  if (action == StopAction.DISCARD) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.primary,
          )
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rec_cancel)) } },
  )
}

/** Which data to record from this sensor; the gear icon on the sensor card opens it. */
@Composable
private fun DataDialog(sensor: SleepSensor, prefs: RecordingPrefs, onDismiss: () -> Unit) {
  var ecg by remember(sensor.id) { mutableStateOf(prefs.ecgEnabled(sensor.id)) }
  var acc by remember(sensor.id) { mutableStateOf(prefs.accEnabled(sensor.id)) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.rec_data_title)) },
      text = {
        Column {
          ToggleRow(stringResource(R.string.rec_config_hr), checked = true, enabled = false) {}
          Text(
              text = stringResource(R.string.rec_config_hr_note),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
          )
          if (sensor.capabilities.ecg) {
            ToggleRow(stringResource(R.string.rec_config_ecg), ecg) {
              ecg = it
              prefs.setEcgEnabled(sensor.id, it)
            }
          }
          if (sensor.capabilities.acc) {
            ToggleRow(stringResource(R.string.rec_config_acc), acc) {
              acc = it
              prefs.setAccEnabled(sensor.id, it)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rec_done)) } },
  )
}

@Composable
private fun SensorCard(
    sensor: SleepSensor?,
    denied: Boolean,
    onSelect: () -> Unit,
    onDisconnect: () -> Unit,
    onDataSettings: () -> Unit,
) {
  TaigaCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
          modifier =
              Modifier.size(48.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            imageVector = Icons.Outlined.Sensors,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        if (sensor == null) {
          Text(
              text = stringResource(R.string.rec_sensor_none_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Text(
              text = stringResource(R.string.rec_sensor_none_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          val connection by sensor.connection.collectAsStateWithLifecycle()
          val battery by sensor.battery.collectAsStateWithLifecycle()
          Text(text = sensor.name, style = MaterialTheme.typography.titleLarge)
          Text(
              text = connectionText(connection),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          battery?.let {
            Text(
                text = stringResource(R.string.rec_battery, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      if (sensor != null && (sensor.capabilities.ecg || sensor.capabilities.acc)) {
        IconButton(onClick = onDataSettings) {
          Icon(
              imageVector = Icons.Outlined.Settings,
              contentDescription = stringResource(R.string.rec_data_settings),
          )
        }
      }
    }
    if (denied) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.rec_permission_denied),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
      )
    }
    Spacer(Modifier.height(16.dp))
    if (sensor == null) {
      PrimaryButton(text = stringResource(R.string.rec_select_sensor), onClick = onSelect)
    } else {
      SecondaryButton(
          text = stringResource(R.string.rec_disconnect),
          onClick = onDisconnect,
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

private fun startRecording(context: Context, sensor: SleepSensor?, prefs: RecordingPrefs) {
  sensor ?: return
  RecordingRepository.setIdle()
  RecordingService.start(
      context,
      sensor,
      StreamConfig(
          ecg = sensor.capabilities.ecg && prefs.ecgEnabled(sensor.id),
          acc = sensor.capabilities.acc && prefs.accEnabled(sensor.id),
      ),
  )
}

@Composable
private fun connectionText(state: ConnectionState) =
    stringResource(
        when (state) {
          ConnectionState.CONNECTED -> R.string.rec_connection_connected
          ConnectionState.CONNECTING -> R.string.rec_connection_connecting
          ConnectionState.DISCONNECTED -> R.string.rec_connection_disconnected
        }
    )

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    Spacer(Modifier.width(8.dp))
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun ReadinessCard(
    readiness: Readiness,
    onBattery: () -> Unit,
    onSettings: () -> Unit,
) {
  if (readiness.ok) {
    StatusBanner(
        kind = StatusKind.Ok,
        title = stringResource(R.string.rec_ready_title),
        body = stringResource(R.string.rec_ready_body),
    )
    return
  }
  val problems = buildList {
    if (!readiness.ignoringBatteryOptimizations) add(R.string.rec_blocked_battery)
    if (!readiness.bucketExempted) add(R.string.rec_blocked_bucket)
    if (readiness.backgroundRestricted) add(R.string.rec_blocked_restricted)
  }
  StatusBanner(
      kind = StatusKind.Warn,
      title = stringResource(R.string.rec_blocked_title),
      body = stringResource(R.string.rec_blocked_body),
      details = problems.map { stringResource(it) },
      actions = {
        if (!readiness.ignoringBatteryOptimizations) {
          PrimaryButton(
              text = stringResource(R.string.rec_fix_battery),
              onClick = onBattery,
              modifier = Modifier.weight(1f),
              containerColor = MaterialTheme.taiga.warn,
              contentColor = MaterialTheme.taiga.onWarn,
          )
        }
        SecondaryButton(
            text = stringResource(R.string.rec_fix_settings),
            onClick = onSettings,
            modifier = Modifier.weight(1f),
            borderColor = MaterialTheme.taiga.warnBorder,
        )
      },
  )
}

@Composable
private fun ActiveCard(status: RecordingStatus) {
  val state = status.state
  TaigaCard {
    Text(
        text =
            stringResource(
                if (status.phase == Phase.STOPPING) R.string.rec_active_stopping
                else R.string.rec_active_title
            ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = RecordingFormat.elapsed(state.session.elapsedMs),
        style = MaterialTheme.typography.displayLarge,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${status.sensorName.orEmpty()} · ${linkText(state.link.state)}",
        style = MaterialTheme.typography.bodyLarge,
    )
    state.power.percent?.let {
      Text(
          text = stringResource(R.string.rec_battery, it),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (!status.liveStreaming) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.rec_active_offline),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@TaigaCard
    }
    state.overall.reason?.let {
      Spacer(Modifier.height(12.dp))
      OverallBanner(state)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.rec_active_hr),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Without skin contact the strap's heart rate is noise, so do not show it as a measurement.
    val offBody = state.contact.state == RecordingState.ContactState.OFF_BODY
    Text(
        text =
            if (offBody)
                stringResource(
                    R.string.rec_active_no_contact_for,
                    RecordingFormat.duration(state.contact.offBodyMs),
                )
            else
                state.heart.bpm?.let { stringResource(R.string.rec_active_hr_value, it) }
                    ?: stringResource(R.string.rec_active_none),
        style = MaterialTheme.typography.headlineMedium,
        color = if (offBody) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    if (status.config?.ecg == true) {
      state.streams[RecordingState.StreamKind.ECG]?.let {
        StreamLine(
            R.string.rec_active_ecg,
            it,
            poor = state.quality.ecg == RecordingState.EcgQuality.POOR,
        )
      }
    }
    if (status.config?.acc == true) {
      state.streams[RecordingState.StreamKind.ACC]?.let { StreamLine(R.string.rec_active_acc, it) }
    }
    state.storage.lastError?.let {
      Spacer(Modifier.height(8.dp))
      Text(
          text = stringResource(R.string.rec_active_failure, it),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

/** The same fixed priority headline the notification shows, formatted for the active screen. */
@Composable
private fun OverallBanner(state: RecordingState) {
  val reason = state.overall.reason ?: return
  val kind =
      if (state.overall.state == RecordingState.OverallState.ERROR) StatusKind.Bad
      else StatusKind.Warn
  val stale =
      state.streams.values.firstOrNull { it.status == RecordingState.StreamStatus.STALE }
  var title = ""
  var body: String? = null
  when (reason) {
    RecordingState.Reason.STORAGE_ERROR -> {
      title = stringResource(R.string.rec_active_storage_error_title)
      body = state.storage.lastError?.let { stringResource(R.string.rec_active_storage_error_body, it) }
    }
    RecordingState.Reason.LINK_LOST -> {
      title = stringResource(R.string.rec_active_link_lost_title)
      body = stringResource(R.string.rec_active_link_lost_body)
    }
    RecordingState.Reason.OFF_BODY -> {
      title = stringResource(R.string.rec_active_off_body_title)
      body = stringResource(R.string.rec_active_off_body_body, RecordingFormat.duration(state.contact.offBodyMs))
    }
    RecordingState.Reason.STREAM_STALE -> {
      title =
          stringResource(
              R.string.rec_active_stream_stalled_title,
              streamLabel(stale?.kind ?: RecordingState.StreamKind.HR),
          )
      body =
          stringResource(
              R.string.rec_active_stream_stalled_body,
              RecordingFormat.age(stale?.lastSampleAgeMs ?: 0),
          )
    }
    RecordingState.Reason.ECG_POOR -> {
      title = stringResource(R.string.rec_active_ecg_poor_title)
      body = stringResource(R.string.rec_active_ecg_poor_body)
    }
    RecordingState.Reason.BATTERY_LOW -> {
      title = stringResource(R.string.rec_active_battery_low_title)
      body = stringResource(R.string.rec_active_battery_low_body, state.power.percent ?: 0)
    }
  }
  StatusBanner(kind = kind, title = title, body = body)
}

@Composable
private fun streamLabel(kind: RecordingState.StreamKind): String =
    stringResource(
        when (kind) {
          RecordingState.StreamKind.HR -> R.string.rec_active_hr
          RecordingState.StreamKind.ECG -> R.string.rec_active_ecg
          RecordingState.StreamKind.ACC -> R.string.rec_active_acc
        }
    )

@Composable
private fun StreamLine(label: Int, stream: RecordingState.Stream, poor: Boolean = false) {
  val name = stringResource(label)
  // The sensor delivers samples in batches about every half second, so the age of the last one
  // jumps around between zero and the batch interval. The monitor owns the staleness decision;
  // this only formats it.
  val text =
      when {
        stream.status == RecordingState.StreamStatus.WAITING ->
            stringResource(R.string.rec_active_stream_waiting, name)
        stream.status == RecordingState.StreamStatus.STALE ->
            stringResource(
                R.string.rec_active_stream_stalled,
                name,
                RecordingFormat.age(stream.lastSampleAgeMs ?: 0),
            )
        poor -> stringResource(R.string.rec_active_stream_poor, name)
        stream.rateHz == null -> stringResource(R.string.rec_active_stream_measuring, name)
        // Rounded to 5 Hz: the batches make the exact figure flicker by a few Hz.
        else ->
            stringResource(
                R.string.rec_active_stream,
                name,
                Math.round(stream.rateHz!! / 5) * 5.0,
            )
      }
  val problem = stream.status == RecordingState.StreamStatus.STALE || poor
  Spacer(Modifier.height(6.dp))
  Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color =
          if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
  )
}

@Composable
private fun linkText(state: RecordingState.LinkState) =
    stringResource(
        when (state) {
          RecordingState.LinkState.CONNECTED -> R.string.rec_connection_connected
          RecordingState.LinkState.CONNECTING -> R.string.rec_connection_connecting
          RecordingState.LinkState.DISCONNECTED -> R.string.rec_connection_disconnected
        }
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val found by SensorHub.found.collectAsStateWithLifecycle()
  val scanning by SensorHub.scanning.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { SensorHub.startScan(context) }
  ModalBottomSheet(
      onDismissRequest = {
        SensorHub.stopScan()
        onDismiss()
      }
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
          text = stringResource(R.string.rec_scan_title),
          style = MaterialTheme.typography.titleLarge,
      )
      Text(
          text =
              stringResource(
                  if (scanning) R.string.rec_scan_searching
                  else if (found.isEmpty()) R.string.rec_scan_none
                  else R.string.rec_scan_title
              ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      found.forEach { s ->
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                      SensorHub.select(context, s)
                      onDismiss()
                    }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Outlined.Sensors, contentDescription = null)
          Spacer(Modifier.width(12.dp))
          Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          Text(
              text =
                  if (s.kind == SensorKind.SIMULATED) stringResource(R.string.rec_scan_debug)
                  else "${s.rssi} dBm",
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      if (!scanning) {
        OutlinedButton(
            onClick = { SensorHub.startScan(context) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
          Text(stringResource(R.string.rec_scan_again))
        }
      }
    }
  }
}

