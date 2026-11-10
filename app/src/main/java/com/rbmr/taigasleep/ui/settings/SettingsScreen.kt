package com.rbmr.taigasleep.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.insights.AlgorithmNames
import com.rbmr.taigasleep.insights.compareVersions
import com.rbmr.taigasleep.insights.effectiveOrder
import com.rbmr.taigasleep.insights.movePreference
import com.rbmr.taigasleep.sync.RawRetentionMode
import com.rbmr.taigasleep.sync.UploadSettings
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SettingsCheckboxRow
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.SettingsGroup
import com.rbmr.taigasleep.ui.components.SettingsRow
import com.rbmr.taigasleep.ui.theme.ThemeMode
import com.rbmr.taigasleep.ui.theme.taiga
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAboutClick: () -> Unit,
    onGuidanceClick: () -> Unit,
    onRemoteClick: () -> Unit,
    onTokensClick: () -> Unit,
    onPersonalClick: () -> Unit,
) {
  val context = LocalContext.current
  val remoteRepository = remember { context.appContainer.remoteRepository }
  val sync = remember { context.appContainer.syncService }
  val remoteStatus by remoteRepository.status.collectAsStateWithLifecycle()
  val uploadSettings = remember { UploadSettings.get(context) }
  val autoUpload by uploadSettings.autoUpload.collectAsStateWithLifecycle()
  val compressUploads by uploadSettings.compressUploads.collectAsStateWithLifecycle()
  val rawRetention by uploadSettings.rawRetention.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var showAppearance by remember { mutableStateOf(false) }
  var showRetention by remember { mutableStateOf(false) }
  val appearanceSubtitle =
      stringResource(
          when (themeMode) {
            ThemeMode.SYSTEM -> R.string.settings_appearance_body_system
            ThemeMode.LIGHT -> R.string.settings_appearance_body_light
            ThemeMode.DARK -> R.string.settings_appearance_body_dark
          }
      )
  val analysisSettingsStore = remember { context.appContainer.analysisSettingsStore }
  val analysisSettings by analysisSettingsStore.settings.collectAsStateWithLifecycle()
  var installedAlgorithms by remember { mutableStateOf<List<AlgorithmDto>>(emptyList()) }
  var installedLoading by remember { mutableStateOf(false) }
  var installedError by remember { mutableStateOf<String?>(null) }
  var showOrder by remember { mutableStateOf(false) }
  var orderDraft by remember { mutableStateOf<List<String>>(emptyList()) }

  LaunchedEffect(remoteStatus) {
    val api = remoteRepository.api()
    if (api == null) {
      installedAlgorithms = emptyList()
      installedError = null
      return@LaunchedEffect
    }
    installedLoading = true
    installedError = null
    sync.installedAlgorithms()
        .onSuccess { installedAlgorithms = it }
        .onFailure { installedError = it.message }
    installedLoading = false
  }

  val installedById =
      remember(installedAlgorithms) {
        installedAlgorithms
            .groupBy { it.id }
            .mapValues { (_, versions) ->
              versions.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }!!
            }
      }
  val orderIds =
      remember(analysisSettings.preferenceOrder, installedById) {
        if (installedById.isEmpty()) {
          analysisSettings.preferenceOrder
        } else {
          effectiveOrder(analysisSettings.preferenceOrder, installedById.keys)
        }
      }
  val orderLabel =
      orderIds.joinToString(" → ") {
        installedById[it]?.name?.ifBlank { AlgorithmNames.display(it) } ?: AlgorithmNames.display(it)
      }

  LazyColumn(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    item {
      ScreenHeader(
          title = stringResource(R.string.settings_title),
          modifier = Modifier.padding(horizontal = 4.dp),
      )
    }

    item {
      SettingsGroup(stringResource(R.string.settings_group_server)) {
        SettingsRow(
            title = stringResource(R.string.settings_server_title),
            subtitle =
                when (val state = remoteStatus) {
                  RemoteStatus.NotConfigured ->
                      stringResource(R.string.server_state_not_configured)
                  is RemoteStatus.Connected ->
                      stringResource(R.string.server_state_connected, state.email)
                  is RemoteStatus.LoginNeeded ->
                      stringResource(R.string.server_state_login_needed_short)
                  is RemoteStatus.Unreachable ->
                      stringResource(R.string.server_state_unreachable_short)
                },
            subtitleColor =
                when (remoteStatus) {
                  is RemoteStatus.Connected -> MaterialTheme.taiga.ok
                  is RemoteStatus.Unreachable -> MaterialTheme.taiga.bad
                  else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            onClick = onRemoteClick,
        )
        if (remoteStatus !is RemoteStatus.NotConfigured) {
          SettingsDivider()
          SettingsRow(
              title = stringResource(R.string.tokens_title),
              subtitle = stringResource(R.string.tokens_body),
              onClick = onTokensClick,
          )
        }
      }
    }
    if (remoteStatus !is RemoteStatus.NotConfigured) {
      item {
        SettingsGroup(stringResource(R.string.settings_group_analysis)) {
          SettingsCheckboxRow(
              title = stringResource(R.string.settings_analysis_auto_title),
              subtitle = stringResource(R.string.settings_analysis_auto_body),
              checked = analysisSettings.auto,
              onCheckedChange = { analysisSettingsStore.setAuto(it) },
          )
          SettingsDivider()
          SettingsRow(
              title = stringResource(R.string.settings_analysis_order_title),
              subtitle =
                  if (orderLabel.isBlank()) {
                    stringResource(R.string.settings_analysis_order_empty)
                  } else {
                    orderLabel
                  },
              onClick = {
                orderDraft = orderIds
                showOrder = true
              },
          )
        }
      }
    }
    item {
      SettingsGroup(stringResource(R.string.settings_group_personal)) {
        SettingsRow(
            title = stringResource(R.string.settings_personal_title),
            subtitle = stringResource(R.string.settings_personal_body),
            onClick = onPersonalClick,
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
              sync.rescheduleAll()
            },
        )
        if (autoUpload.enabled) {
          SettingsDivider()
          SettingsCheckboxRow(
              title = stringResource(R.string.settings_auto_upload_wifi),
              checked = autoUpload.wifiOnly,
              onCheckedChange = {
                uploadSettings.setWifiOnly(it)
                sync.rescheduleAll()
              },
          )
          SettingsDivider()
          SettingsCheckboxRow(
              title = stringResource(R.string.settings_auto_upload_charging),
              checked = autoUpload.chargingOnly,
              onCheckedChange = {
                uploadSettings.setChargingOnly(it)
                sync.rescheduleAll()
              },
          )
        }
        SettingsDivider()
        SettingsCheckboxRow(
            title = stringResource(R.string.settings_upload_compress_title),
            subtitle = stringResource(R.string.settings_upload_compress_body),
            checked = compressUploads,
            onCheckedChange = { uploadSettings.setCompressUploads(it) },
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_raw_retention_title),
            subtitle = rawRetentionLabel(rawRetention),
            onClick = { showRetention = true },
        )
      }
    }
    item {
      SettingsGroup(stringResource(R.string.settings_group_app)) {
        // A second way into the flow, always available. The state stays on Home's card only.
        SettingsRow(
            title = stringResource(R.string.settings_prevent_title),
            subtitle = stringResource(R.string.settings_prevent_body),
            onClick = onGuidanceClick,
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
            subtitle = stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME),
            onClick = onAboutClick,
        )
      }
    }
  }

  if (showRetention) {
    AlertDialog(
        onDismissRequest = { showRetention = false },
        title = { Text(stringResource(R.string.settings_raw_retention_title)) },
        text = {
          Column {
            Text(
                text = stringResource(R.string.settings_raw_retention_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            RawRetentionMode.entries.forEach { mode ->
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .clickable {
                            uploadSettings.setRawRetention(mode)
                            showRetention = false
                            scope.launch { sync.enforceRawRetention() }
                          }
                          .padding(vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(selected = rawRetention == mode, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(rawRetentionLabel(mode))
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { showRetention = false }) {
            Text(stringResource(R.string.appearance_done))
          }
        },
    )
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

  if (showOrder) {
    AlertDialog(
        onDismissRequest = { showOrder = false },
        title = { Text(stringResource(R.string.settings_analysis_order_title)) },
        text = {
          Column {
            Text(
                text = stringResource(R.string.settings_analysis_order_dialog_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (installedLoading) {
              Text(
                  text = stringResource(R.string.settings_analysis_order_loading),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(bottom = 8.dp),
              )
            } else if (installedError != null) {
              Text(
                  text = stringResource(R.string.settings_analysis_order_error),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.padding(bottom = 8.dp),
              )
            }
            if (orderDraft.isEmpty()) {
              Text(
                  text = stringResource(R.string.settings_analysis_order_empty),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            orderDraft.forEachIndexed { index, id ->
              val algorithm = installedById[id]
              Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(Modifier.weight(1f)) {
                  Text(
                      text =
                          algorithm?.name?.ifBlank { AlgorithmNames.display(id) }
                              ?: AlgorithmNames.display(id),
                      style = MaterialTheme.typography.titleSmall,
                  )
                }
                IconButton(
                    onClick = { orderDraft = movePreference(orderDraft, id, -1) },
                    enabled = index > 0,
                ) {
                  Icon(
                      imageVector = Icons.Outlined.KeyboardArrowUp,
                      contentDescription = stringResource(R.string.settings_analysis_order_up),
                  )
                }
                IconButton(
                    onClick = { orderDraft = movePreference(orderDraft, id, 1) },
                    enabled = index < orderDraft.lastIndex,
                ) {
                  Icon(
                      imageVector = Icons.Outlined.KeyboardArrowDown,
                      contentDescription = stringResource(R.string.settings_analysis_order_down),
                  )
                }
              }
            }
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                analysisSettingsStore.setPreferenceOrder(
                    orderDraft +
                        analysisSettings.preferenceOrder.filter { it !in orderDraft }
                )
                showOrder = false
              }
          ) {
            Text(stringResource(R.string.settings_analysis_order_save))
          }
        },
        dismissButton = {
          TextButton(onClick = { showOrder = false }) {
            Text(stringResource(R.string.settings_analysis_order_cancel))
          }
        },
    )
  }
}

@Composable
private fun rawRetentionLabel(mode: RawRetentionMode): String =
    stringResource(
        when (mode) {
          RawRetentionMode.NEVER -> R.string.raw_retention_never
          RawRetentionMode.IMMEDIATE -> R.string.raw_retention_immediate
          RawRetentionMode.DAYS_5 -> R.string.raw_retention_days_5
          RawRetentionMode.DAYS_14 -> R.string.raw_retention_days_14
          RawRetentionMode.DAYS_30 -> R.string.raw_retention_days_30
        }
    )
