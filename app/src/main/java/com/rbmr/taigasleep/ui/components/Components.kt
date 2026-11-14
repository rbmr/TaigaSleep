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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
    contentPadding: PaddingValues = PaddingValues(MaterialTheme.taiga.space.m),
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

/**
 * The one switch of the app. Off reads as a working switch that is off: an outlined track with a
 * visible border and a mid-grey thumb. A disabled switch keeps the faded Material colours, so the
 * two never look alike. The theme's own outline is a pale divider tone, so the off colours come
 * from onSurfaceVariant instead.
 */
@Composable
fun TaigaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  val off = MaterialTheme.colorScheme.onSurfaceVariant
  Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = modifier,
      enabled = enabled,
      colors =
          SwitchDefaults.colors(
              uncheckedThumbColor = off,
              uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
              uncheckedBorderColor = off,
          ),
  )
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
