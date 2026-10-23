package com.rbmr.taigasleep.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.LocalComingSoon
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SettingsRow

@Composable
fun SettingsScreen() {
  val comingSoon = LocalComingSoon.current
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
          subtitle = stringResource(R.string.settings_appearance_body),
          onClick = comingSoon,
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
}
