package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The one bottom sheet of the app. It opens fully expanded (no half-open state to jump between),
 * never grows above the status bar, and scrolls its content inside the sheet, so dragging works
 * the same for every sheet. The content gets the system navigation bar inset at its end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaigaSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val density = LocalDensity.current
  val screen = LocalConfiguration.current.screenHeightDp.dp
  val statusBar = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
  // The drag handle and its padding sit above the content.
  val maxContent = (screen - statusBar - SheetHandleReserve).coerceAtLeast(0.dp)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(max = maxContent)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        content = content,
    )
  }
}

private val SheetHandleReserve = 48.dp
