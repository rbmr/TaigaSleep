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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ThemeMode
import com.rbmr.taigasleep.ui.LocalComingSoon
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SettingsRow

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
  val comingSoon = LocalComingSoon.current
  var showAppearance by remember { mutableStateOf(false) }
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
      contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      ScreenHeader(
          kicker = stringResource(R.string.settings_kicker),
          title = stringResource(R.string.settings_title),
          subtitle = "",
      )
    }

    item {
      SettingsRow(
          icon = Icons.Outlined.BatterySaver,
          title = stringResource(R.string.settings_prevent_title),
          subtitle = stringResource(R.string.settings_prevent_body),
          onClick = comingSoon,
      )
    }
    item {
      SettingsRow(
          icon = Icons.Outlined.Cloud,
          title = stringResource(R.string.settings_remote_title),
          subtitle = stringResource(R.string.settings_remote_body),
          onClick = comingSoon,
      )
    }
    item {
      SettingsRow(
          icon = Icons.Outlined.DarkMode,
          title = stringResource(R.string.settings_appearance_title),
          subtitle = appearanceSubtitle,
          onClick = { showAppearance = true },
      )
    }
    item {
      SettingsRow(
          icon = Icons.Outlined.Info,
          title = stringResource(R.string.settings_about_title),
          subtitle = stringResource(R.string.settings_about_body),
          onClick = comingSoon,
      )
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
}
