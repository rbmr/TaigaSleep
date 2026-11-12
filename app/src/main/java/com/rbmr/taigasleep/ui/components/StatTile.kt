package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * A label above one value. With [info] the tile is a button: tapping it opens the item's
 * [InfoSheet], so the explanation lives with the number it explains.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
    info: InfoContent? = null,
) {
  var showInfo by remember { mutableStateOf(false) }
  if (info != null) {
    Pressable(
        onClick = { showInfo = true },
        modifier = modifier,
        shape = MaterialTheme.taiga.shape.card,
        role = Role.Button,
        onClickLabel = label,
    ) {
      StatTileBody(label, value, valueColor, caption)
    }
  } else {
    StatTileBody(label, value, valueColor, caption, modifier)
  }
  if (showInfo && info != null) {
    InfoSheet(info = info, onDismiss = { showInfo = false })
  }
}

@Composable
private fun StatTileBody(
    label: String,
    value: String,
    valueColor: Color,
    caption: String?,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier.heightIn(min = 92.dp),
      shape = MaterialTheme.taiga.shape.card,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.xs),
    ) {
      Text(
          text = label,
          style = MaterialTheme.taiga.text.meta,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = value,
          style = MaterialTheme.taiga.text.section,
          color = valueColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      if (caption != null) {
        Text(
            text = caption,
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * A label above a value with no card of its own, for the figures that sit beside a larger one
 * instead of forming a grid, such as the summary at the head of a recording.
 */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.xs)) {
    Text(
        text = label,
        style = MaterialTheme.taiga.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = value,
        style = MaterialTheme.taiga.text.row,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatTilePreview() {
  PreviewTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatTile(
          label = "Average heart rate",
          value = "54 bpm",
          modifier = Modifier.weight(1f),
          info = InfoContent("Average heart rate", "The mean of all clean samples."),
      )
      StatTile(label = "Data quality", value = "86 / 100", modifier = Modifier.weight(1f))
    }
    StatTile(label = "Time asleep", value = "7 h 52 min", caption = "of 8 h 12 min")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      LabeledValue("Total asleep", "7 h 52 min")
      LabeledValue("Data quality", "86 / 100")
    }
  }
}
