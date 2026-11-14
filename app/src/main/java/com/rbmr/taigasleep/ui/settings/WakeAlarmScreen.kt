package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.recording.EndRecordingOnAlarm
import com.rbmr.taigasleep.recording.WakeAlarmSettings
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen

/** Settings → Wake-up alarm: what the alarm does with the running recording when it rings. */
@Composable
fun WakeAlarmScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val settings = remember { WakeAlarmSettings.get(context) }
  var choice by remember { mutableStateOf(settings.endRecordingOnAlarm) }

  TaigaScreen(title = stringResource(R.string.settings_wake_alarm_title), onBack = onBack) {
    Section(
        title = stringResource(R.string.settings_wake_alarm_group),
        info =
            InfoContent(
                title = stringResource(R.string.settings_wake_alarm_title),
                body = stringResource(R.string.settings_wake_alarm_body),
            ),
    ) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        EndRecordingOnAlarm.entries.forEachIndexed { index, option ->
          if (index > 0) SettingsDivider()
          ListRow(
              title = endRecordingLabel(option),
              trailing = ListRowTrailing.Radio(choice == option),
              onClick = {
                choice = option
                settings.endRecordingOnAlarm = option
              },
          )
        }
      }
    }
  }
}

/** The three choices by the names the issue and the alarm use: Always, Optional, Never. */
@Composable
internal fun endRecordingLabel(option: EndRecordingOnAlarm): String =
    stringResource(
        when (option) {
          EndRecordingOnAlarm.ALWAYS -> R.string.wake_end_always
          EndRecordingOnAlarm.OPTIONAL -> R.string.wake_end_optional
          EndRecordingOnAlarm.NEVER -> R.string.wake_end_never
        }
    )
