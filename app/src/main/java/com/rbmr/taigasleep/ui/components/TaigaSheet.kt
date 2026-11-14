package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one bottom sheet of the app. It opens fully expanded (no half-open state to jump between)
 * and scrolls its content inside the sheet, so dragging works the same for every sheet.
 *
 * The content takes the height the sheet leaves below its handle and scrolls when it is taller:
 * it is given that height by the sheet itself rather than a figure computed here, because a
 * second opinion about the available height makes the content and the sheet's own drag fight
 * over the last few pixels. The content gets the system navigation bar inset at its end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaigaSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        content = content,
    )
  }
}
