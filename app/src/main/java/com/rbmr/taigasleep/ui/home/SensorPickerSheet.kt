package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.ui.components.DestructiveButton
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The tracker picker: what is in range, a button to look again, and Disconnect while a tracker is
 * connected, because the sheet is now the one place a tracker is chosen or left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensorPickerSheet(
    picker: SensorPicker,
    connected: TrackerView?,
    onSelect: (FoundSensor) -> Unit,
    onScanAgain: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = space.m)
                .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
      Text(
          text = stringResource(R.string.rec_scan_title),
          style = MaterialTheme.taiga.text.section,
      )
      if (picker.scanning || picker.found.isEmpty()) {
        Text(
            text =
                stringResource(
                    if (picker.scanning) R.string.rec_scan_searching else R.string.rec_scan_none
                ),
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      picker.found.forEach { found ->
        ListRow(
            title = found.name,
            icon = Icons.Outlined.Sensors,
            value =
                if (found.kind == SensorKind.SIMULATED) stringResource(R.string.rec_scan_debug)
                else null,
            trailing = ListRowTrailing.None,
            onClick = { onSelect(found) },
        )
      }
      if (!picker.scanning) {
        SecondaryButton(text = stringResource(R.string.rec_scan_again), onClick = onScanAgain)
      }
      if (connected != null) {
        SettingsDivider()
        Text(
            text = stringResource(R.string.rec_sheet_connected, connected.name),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DestructiveButton(
            text = stringResource(R.string.rec_disconnect),
            onClick = onDisconnect,
        )
      }
    }
  }
}
