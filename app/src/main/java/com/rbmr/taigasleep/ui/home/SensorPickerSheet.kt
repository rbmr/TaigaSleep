package com.rbmr.taigasleep.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.theme.taiga
import com.rbmr.taigasleep.ui.components.TaigaCard

/** The sensor card: which tracker is selected, whether it is here, and the two things to do with it. */
@Composable
internal fun SensorCard(
    tracker: TrackerView?,
    denied: Boolean,
    canConfigure: Boolean,
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
        if (tracker == null) {
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
          Text(text = tracker.name, style = MaterialTheme.typography.titleLarge)
          Text(
              text = connectionText(tracker.connection),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          tracker.battery?.let {
            Text(
                text = stringResource(R.string.rec_battery, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      if (tracker != null && canConfigure) {
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
    if (tracker == null) {
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

@Composable
internal fun connectionText(state: ConnectionState) =
    stringResource(
        when (state) {
          ConnectionState.CONNECTED -> R.string.rec_connection_connected
          ConnectionState.CONNECTING -> R.string.rec_connection_connecting
          ConnectionState.DISCONNECTED -> R.string.rec_connection_disconnected
        }
    )

/** The strap picker: what is in range, and a button to look again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensorPickerSheet(
    picker: SensorPicker,
    onSelect: (FoundSensor) -> Unit,
    onScanAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
          text = stringResource(R.string.rec_scan_title),
          style = MaterialTheme.typography.titleLarge,
      )
      if (picker.scanning || picker.found.isEmpty()) {
        Text(
            text =
                stringResource(
                    if (picker.scanning) R.string.rec_scan_searching else R.string.rec_scan_none
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      picker.found.forEach { s ->
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.taiga.shape.control)
                    .clickable { onSelect(s) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Outlined.Sensors, contentDescription = null)
          Spacer(Modifier.width(12.dp))
          Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          if (s.kind == SensorKind.SIMULATED) {
            Text(
                text = stringResource(R.string.rec_scan_debug),
                style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
      if (!picker.scanning) {
        OutlinedButton(
            onClick = onScanAgain,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.taiga.shape.control,
        ) {
          Text(stringResource(R.string.rec_scan_again))
        }
      }
    }
  }
}
