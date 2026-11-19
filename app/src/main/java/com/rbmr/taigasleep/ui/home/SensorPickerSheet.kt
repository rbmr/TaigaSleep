package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.MaterialTheme
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
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The tracker picker: the connected tracker first, then what is in range, a button to look again
 * and, while a tracker is connected, Disconnect, because the sheet is now the one place a tracker
 * is chosen or left.
 */
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
  TaigaSheet(onDismiss = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = space.m)
                .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.l),
    ) {
      if (connected != null) {
        Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
          ListRow(
              title = connected.name,
              icon = Icons.Outlined.Sensors,
              trailing =
                  ListRowTrailing.Pill(
                      label = stringResource(R.string.rec_connection_connected),
                      state = StatusPillState.Ok,
                  ),
          )
          DestructiveButton(
              text = stringResource(R.string.rec_disconnect),
              onClick = onDisconnect,
          )
        }
      }
      Section(title = stringResource(R.string.rec_scan_title)) {
        // The connected tracker is the row above, so it is not listed a second time here.
        val others = picker.found.filter { it.name != connected?.name }
        if (picker.scanning || others.isEmpty()) {
          Text(
              text =
                  stringResource(
                      if (picker.scanning) R.string.rec_scan_searching else R.string.rec_scan_none
                  ),
              style = MaterialTheme.taiga.text.body,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        others.forEach { found ->
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
      }
    }
  }
}
