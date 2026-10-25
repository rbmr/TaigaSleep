package com.rbmr.taigasleep.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.GuidanceStore
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ThemeMode
import com.rbmr.taigasleep.guidance.GuidanceFlows
import com.rbmr.taigasleep.guidance.PreviewDevice
import com.rbmr.taigasleep.guidance.Progress
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.RemoteStatus
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SettingsCheckboxRow
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.SettingsGroup
import com.rbmr.taigasleep.ui.components.SettingsRow
import com.rbmr.taigasleep.ui.theme.taiga
import com.rbmr.taigasleep.upload.UploadScheduler
import com.rbmr.taigasleep.upload.UploadSettings

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAboutClick: () -> Unit,
    onGuidanceClick: () -> Unit,
    onRemoteClick: () -> Unit,
) {
  val context = LocalContext.current
  val remoteRepository = remember { RemoteRepository.of(context) }
  val remoteStatus by remoteRepository.status.collectAsStateWithLifecycle()
  val uploadSettings = remember { UploadSettings.get(context) }
  val autoUpload by uploadSettings.autoUpload.collectAsStateWithLifecycle()
  var showAppearance by remember { mutableStateOf(false) }
  var showPreviewPicker by remember { mutableStateOf(false) }
  val guidanceStore = remember { GuidanceStore(context) }
  fun guidanceReady(): Boolean {
    val match = GuidanceFlows.currentMatch(GuidanceFlows.previewOf(guidanceStore))
    return Progress.isReady(context, guidanceStore, GuidanceFlows.selected(match, guidanceStore))
  }
  var ready by remember { mutableStateOf(guidanceReady()) }
  // Settings may have changed while we were away, so check again on every resume.
  LifecycleResumeEffect(Unit) {
    ready = guidanceReady()
    onPauseOrDispose {}
  }
  val appearanceSubtitle =
      stringResource(
          when (themeMode) {
            ThemeMode.SYSTEM -> R.string.settings_appearance_body_system
            ThemeMode.LIGHT -> R.string.settings_appearance_body_light
            ThemeMode.DARK -> R.string.settings_appearance_body_dark
          }
      )

  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    item {
      ScreenHeader(
          kicker = stringResource(R.string.settings_kicker),
          title = stringResource(R.string.settings_title),
          modifier = Modifier.padding(horizontal = 4.dp),
      )
    }

    item {
      SettingsGroup(stringResource(R.string.settings_group_server)) {
        SettingsRow(
            title = stringResource(R.string.settings_remote_title),
            subtitle =
                when (val state = remoteStatus) {
                  RemoteStatus.NotConfigured ->
                      stringResource(R.string.remote_state_not_configured)
                  is RemoteStatus.Connected ->
                      stringResource(R.string.remote_state_connected, state.email)
                  is RemoteStatus.LoginNeeded ->
                      stringResource(R.string.remote_state_login_needed_short)
                  is RemoteStatus.Unreachable ->
                      stringResource(R.string.remote_state_unreachable_short)
                },
            subtitleColor =
                when (remoteStatus) {
                  is RemoteStatus.Connected -> MaterialTheme.taiga.ok
                  is RemoteStatus.Unreachable -> MaterialTheme.taiga.bad
                  else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            onClick = onRemoteClick,
        )
      }
    }
    item {
      SettingsGroup(stringResource(R.string.settings_group_recordings)) {
        SettingsCheckboxRow(
            title = stringResource(R.string.settings_auto_upload_title),
            subtitle = stringResource(R.string.settings_auto_upload_body),
            checked = autoUpload.enabled,
            onCheckedChange = {
              uploadSettings.setEnabled(it)
              UploadScheduler.rescheduleAll(context)
            },
        )
        if (autoUpload.enabled) {
          SettingsDivider()
          SettingsCheckboxRow(
              title = stringResource(R.string.settings_auto_upload_wifi),
              checked = autoUpload.wifiOnly,
              onCheckedChange = {
                uploadSettings.setWifiOnly(it)
                UploadScheduler.rescheduleAll(context)
              },
          )
          SettingsDivider()
          SettingsCheckboxRow(
              title = stringResource(R.string.settings_auto_upload_charging),
              checked = autoUpload.chargingOnly,
              onCheckedChange = {
                uploadSettings.setChargingOnly(it)
                UploadScheduler.rescheduleAll(context)
              },
          )
        }
        SettingsDivider()
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
          Text(
              text = stringResource(R.string.settings_upload_retention_title),
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              text = stringResource(R.string.settings_upload_retention_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
    }
    item {
      SettingsGroup(stringResource(R.string.settings_group_app)) {
        SettingsRow(
            title = stringResource(R.string.settings_prevent_title),
            subtitle =
                "● " +
                    stringResource(
                        if (ready) R.string.g_settings_ready else R.string.g_settings_needs_attention
                    ),
            subtitleColor = if (ready) MaterialTheme.taiga.ok else MaterialTheme.taiga.warn,
            onClick = onGuidanceClick,
            // Debug builds only: pick which device's guidance to preview.
            onLongClick = if (BuildConfig.DEBUG) ({ showPreviewPicker = true }) else null,
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_appearance_title),
            subtitle = appearanceSubtitle,
            onClick = { showAppearance = true },
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_about_title),
            subtitle = stringResource(R.string.settings_about_body),
            onClick = onAboutClick,
        )
      }
    }
  }

  if (showAppearance) {
    AlertDialog(
        onDismissRequest = { showAppearance = false },
        title = { Text(stringResource(R.string.settings_appearance_title)) },
        text = {
          Column {
            ThemeMode.entries.forEach { mode ->
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .clickable {
                            onThemeModeChange(mode)
                            showAppearance = false
                          }
                          .padding(vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(
                    selected = themeMode == mode,
                    onClick = {
                      onThemeModeChange(mode)
                      showAppearance = false
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text =
                        stringResource(
                            when (mode) {
                              ThemeMode.SYSTEM -> R.string.appearance_system
                              ThemeMode.LIGHT -> R.string.appearance_light
                              ThemeMode.DARK -> R.string.appearance_dark
                            }
                        )
                )
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showAppearance = false }) {
            Text(stringResource(R.string.appearance_done))
          }
        },
    )
  }

  if (showPreviewPicker) {
    PreviewPicker(
        store = guidanceStore,
        onDismiss = {
          showPreviewPicker = false
          ready = guidanceReady()
        },
    )
  }
}

@Composable
private fun PreviewPicker(store: GuidanceStore, onDismiss: () -> Unit) {
  var selected by remember { mutableStateOf(store.previewDevice) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.g_preview_title)) },
      text = {
        Column {
          (listOf<PreviewDevice?>(null) + PreviewDevice.entries).forEach { device ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                          store.previewDevice = device
                          selected = device
                          onDismiss()
                        }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = selected == device, onClick = null)
              Spacer(Modifier.width(12.dp))
              Text(device?.label ?: stringResource(R.string.g_preview_real))
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.appearance_done)) }
      },
  )
}
