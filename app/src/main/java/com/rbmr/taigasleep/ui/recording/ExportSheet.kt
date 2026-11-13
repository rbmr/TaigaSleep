package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * What the export holds. Nothing is prepared until the archive is asked for; the export itself
 * runs on the screen behind this sheet.
 */
@Composable
internal fun ExportSheet(
    state: RecordingDetailUiState,
    onExportRaw: (Boolean) -> Unit,
    onExportMetadata: (Boolean) -> Unit,
    onExportResults: (Boolean) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  TaigaSheet(onDismiss = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = space.m)
                .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      Text(
          text = stringResource(R.string.night_export_contents),
          style = MaterialTheme.taiga.text.section,
      )
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        ListRow(
            title = stringResource(R.string.night_export_raw),
            trailing = ListRowTrailing.Checkbox(checked = state.exportRaw),
            enabled = state.rawExportable && !state.exportBusy,
            onClick = { onExportRaw(!state.exportRaw) },
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_export_metadata),
            trailing = ListRowTrailing.Checkbox(checked = state.exportMetadata),
            enabled = state.metadataExportable && !state.exportBusy,
            onClick = { onExportMetadata(!state.exportMetadata) },
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.night_export_results),
            subtitle =
                if (state.resultsExportable) {
                  null
                } else {
                  stringResource(R.string.night_export_results_missing)
                },
            trailing = ListRowTrailing.Checkbox(checked = state.exportResults),
            enabled = state.resultsExportable && !state.exportBusy,
            onClick = { onExportResults(!state.exportResults) },
        )
      }
      PrimaryButton(
          text = stringResource(R.string.night_export_action),
          onClick = {
            onDismiss()
            onExport()
          },
          enabled = state.canExport && !state.exportBusy,
          size = ButtonSize.Regular,
      )
    }
  }
}
