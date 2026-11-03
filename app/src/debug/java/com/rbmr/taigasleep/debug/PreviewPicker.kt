package com.rbmr.taigasleep.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.rbmr.taigasleep.GuidanceStore
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.guidance.PreviewDevice

/** Picks which device's guidance to preview; opened by long-pressing Home's readiness card. */
@Composable
fun GuidancePreviewPicker(store: GuidanceStore, onDismiss: () -> Unit) {
  var selected by remember { mutableStateOf(store.previewDevice) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.g_preview_title)) },
      text = {
        Column {
          (listOf<PreviewDevice?>(null) + PreviewDevice.entries).forEach { device ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                          store.previewDevice = device
                          selected = device
                          onDismiss()
                        }
                        .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = selected == device, onClick = null)
              Spacer(Modifier.width(12.dp))
              Text(device?.label ?: stringResource(R.string.g_preview_real))
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.appearance_done)) }
      },
  )
}
