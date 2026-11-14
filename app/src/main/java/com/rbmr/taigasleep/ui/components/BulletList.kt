package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Separate items as a list: one small bullet and one text per item, with space between the items.
 * Long items wrap under their own text, not under the bullet.
 */
@Composable
fun BulletList(items: List<String>, modifier: Modifier = Modifier) {
  val space = MaterialTheme.taiga.space
  val style = MaterialTheme.taiga.text.body
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(space.s)) {
    items.forEach { item ->
      Row(horizontalArrangement = Arrangement.spacedBy(space.s)) {
        // The bullet sits on the first line: the line box is centred on the text's own height.
        Box(Modifier.padding(top = 9.dp).size(6.dp).background(color, CircleShape))
        Text(text = item, style = style, color = color, modifier = Modifier.weight(1f))
      }
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BulletListPreview() {
  PreviewTheme {
    BulletList(
        listOf(
            "Part of the night had no heart rate.",
            "A long note that wraps onto a second line shows that the text stays aligned under itself.",
        )
    )
  }
}
