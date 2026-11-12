package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * A card: the surface a group of content or rows sits on. Rows that need no gaps between them pass
 * a [contentPadding] of zero and separate themselves with [SettingsDivider].
 */
@Composable
fun TaigaCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.taiga.shape.card,
      color = containerColor,
      border = BorderStroke(1.dp, borderColor),
  ) {
    Column(modifier = Modifier.padding(contentPadding), content = content)
  }
}

/** The hairline between two rows of the same card. */
@Composable
fun SettingsDivider() {
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TaigaCardPreview() {
  PreviewTheme {
    TaigaCard {
      Text("A card holds a group", style = MaterialTheme.taiga.text.body)
      SettingsDivider()
      Text("Rows are separated by hairlines", style = MaterialTheme.taiga.text.meta)
    }
  }
}
