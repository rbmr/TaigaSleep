package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen

/** Settings → Smart alarm: whether the ringing alarm ends the recording. */
@Composable
fun SmartAlarmSettingsScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val viewModel: SmartAlarmSettingsViewModel =
      viewModel(factory = remember { SmartAlarmSettingsViewModel.Factory(context) })
  val options by viewModel.options.collectAsStateWithLifecycle()

  TaigaScreen(title = stringResource(R.string.wake_title), onBack = onBack) {
    Section(
        title = stringResource(R.string.settings_wake_end_group),
        info =
            InfoContent(
                title = stringResource(R.string.settings_wake_end_group),
                body = stringResource(R.string.settings_wake_end_body),
            ),
    ) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        options.forEachIndexed { index, option ->
          if (index > 0) SettingsDivider()
          ListRow(
              title = stringResource(option.label),
              trailing = ListRowTrailing.Radio(option.selected),
              onClick = { viewModel.select(index) },
          )
        }
      }
    }
  }
}
