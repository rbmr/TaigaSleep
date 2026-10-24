package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/** One bar per step; the first [current] bars are filled. [description] is read by TalkBack. */
@Composable
fun SegmentedProgress(
    steps: Int,
    current: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    repeat(steps) { index ->
      Box(
          Modifier.weight(1f)
              .height(5.dp)
              .background(
                  color =
                      if (index < current) MaterialTheme.colorScheme.primary
                      else MaterialTheme.taiga.segment,
                  shape = RoundedCornerShape(3.dp),
              )
      )
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SegmentedProgressPreview() {
  PreviewTheme { SegmentedProgress(steps = 6, current = 3, description = "Step 3 of 6") }
}
