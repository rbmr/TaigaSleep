package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
 *
 * The status bar inset is carried by the sheet itself, and no content insets are handed down. The
 * bottom sheet otherwise takes the insets it is given from where it currently is: it consumes the
 * top inset by its drag offset and pads the content by what is left. The content then stands still
 * for the first status bar height of a downward drag, and the sheet's height and drag anchors
 * change while it is dragged, so a sheet as tall as the screen jumps under the finger and keeps
 * settling again. Carrying the inset here keeps the layout of the sheet and its content the same
 * at every offset, so the whole sheet follows the finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaigaSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = state,
      modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
      contentWindowInsets = { WindowInsets(0) },
  ) {
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
