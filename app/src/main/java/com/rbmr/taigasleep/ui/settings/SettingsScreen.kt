package com.rbmr.taigasleep.ui.settings

import com.rbmr.taigasleep.ui.theme.taiga
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.sync.UploadSettings
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SegmentedControl
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.ThemeMode

/**
 * Settings: the server, the personal details and the app, each a short list of rows. Nothing here
 * explains itself; the rare choices live one level deeper, behind Uploads and Analysis.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAboutClick: () -> Unit,
    onGuidanceClick: () -> Unit,
    onRemoteClick: () -> Unit,
    onTokensClick: () -> Unit,
    onPersonalClick: () -> Unit,
    onUploadsClick: () -> Unit,
    onAnalysisClick: () -> Unit,
) {
  val context = LocalContext.current
  val remoteRepository = remember { context.appContainer.remoteRepository }
  val remoteStatus by remoteRepository.status.collectAsStateWithLifecycle()
  val address by remoteRepository.address.collectAsStateWithLifecycle()
  val uploadSettings = remember { UploadSettings.get(context) }
  val autoUpload by uploadSettings.autoUpload.collectAsStateWithLifecycle()
  val analysisSettingsStore = remember { context.appContainer.analysisSettingsStore }
  val analysisSettings by analysisSettingsStore.settings.collectAsStateWithLifecycle()
  val personalDataStore = remember { context.appContainer.personalDataStore }
  val personalData by personalDataStore.data.collectAsStateWithLifecycle()

  // The count is the server's answer, so the row asks for it once each time the screen is opened.
  val tokensViewModel: AccessTokensViewModel =
      viewModel(factory = remember { AccessTokensViewModel.Factory(context) })
  val tokens by tokensViewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(remoteStatus) {
    if (remoteStatus !is RemoteStatus.NotConfigured) tokensViewModel.refresh()
  }

  val serverSet = remoteStatus !is RemoteStatus.NotConfigured
  val tokenCount =
      tokens.tokens.size.takeIf { !tokens.loading && tokens.error == null }?.toString()

  TaigaScreen(title = stringResource(R.string.settings_title)) {
    Section(title = stringResource(R.string.settings_group_server)) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        ServerRow(
            status = remoteStatus,
            host = hostLabel(address),
            email =
                (remoteStatus as? RemoteStatus.Connected)
                    ?.email
                    ?.takeIf { it.isNotBlank() }
                    ?: remoteRepository.savedEmail.takeIf { serverSet && it.isNotBlank() },
            onClick = onRemoteClick,
        )
        if (serverSet) {
          SettingsDivider()
          ListRow(
              title = stringResource(R.string.tokens_title),
              value = tokenCount,
              onClick = onTokensClick,
          )
        }
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.settings_uploads_title),
            value = automaticOrManual(autoUpload.enabled),
            onClick = onUploadsClick,
        )
        if (serverSet) {
          SettingsDivider()
          ListRow(
              title = stringResource(R.string.settings_analysis_title),
              value = automaticOrManual(analysisSettings.auto),
              onClick = onAnalysisClick,
          )
        }
      }
    }
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      ListRow(
          title = stringResource(R.string.settings_personal_title),
          value = personalSetValue(personalData),
          onClick = onPersonalClick,
      )
    }
    Spacer(Modifier.height(MaterialTheme.taiga.space.s))
    Section(title = stringResource(R.string.settings_group_app)) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        ListRow(
            title = stringResource(R.string.settings_appearance_title),
            trailing = ListRowTrailing.None,
            below = {
              SegmentedControl(
                  options =
                      listOf(
                          stringResource(R.string.appearance_system),
                          stringResource(R.string.appearance_light),
                          stringResource(R.string.appearance_dark),
                      ),
                  selectedIndex = ThemeMode.entries.indexOf(themeMode),
                  onSelect = { onThemeModeChange(ThemeMode.entries[it]) },
                  modifier = Modifier.fillMaxWidth(),
                  fillWidth = true,
              )
            },
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.settings_prevent_title),
            onClick = onGuidanceClick,
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.settings_about_title),
            value = BuildConfig.VERSION_NAME,
            onClick = onAboutClick,
        )
      }
    }
  }
}

/** The account: the host it reaches, the account on it and one pill that says how it stands. */
@Composable
private fun ServerRow(
    status: RemoteStatus,
    host: String?,
    email: String?,
    onClick: () -> Unit,
) {
  val (label, state) =
      when (status) {
        RemoteStatus.NotConfigured ->
            stringResource(R.string.server_status_none) to StatusPillState.Warn
        is RemoteStatus.Connected ->
            stringResource(R.string.server_status_connected) to StatusPillState.Ok
        is RemoteStatus.LoginNeeded ->
            stringResource(R.string.server_state_login_needed_short) to StatusPillState.Warn
        is RemoteStatus.Unreachable ->
            stringResource(R.string.server_state_unreachable_short) to StatusPillState.Bad
      }
  ListRow(
      title = host ?: stringResource(R.string.settings_server_title),
      subtitle = email,
      trailing = ListRowTrailing.Pill(label = label, state = state),
      onClick = onClick,
  )
}

/** The address without its scheme, which is what the person typed: sleep.example.com. */
private fun hostLabel(address: String?): String? =
    address?.substringAfter("://")?.trimEnd('/')?.takeIf { it.isNotBlank() }

@Composable
private fun automaticOrManual(automatic: Boolean): String =
    stringResource(
        if (automatic) R.string.settings_value_automatic else R.string.settings_value_manual
    )

/** "3 of 5 set": how many of the optional personal details are filled in. */
@Composable
private fun personalSetValue(data: PersonalData): String {
  val values =
      listOf(data.dateOfBirth, data.sex, data.weightKg, data.usualBedtime, data.usualWakeTime)
  return stringResource(R.string.settings_personal_set, values.count { it != null }, values.size)
}
