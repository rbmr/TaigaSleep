package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.TaigaTheme

/** Background and spacing shared by the component previews; it follows the preview's uiMode. */
@Composable
internal fun PreviewTheme(content: @Composable ColumnScope.() -> Unit) {
  TaigaTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          content = content,
      )
    }
  }
}
