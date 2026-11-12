package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
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

/** What one row's 8 dp track reports. */
sealed interface ProgressTrackState {
  /** Neither moving nor planned: the empty track. */
  data object Empty : ProgressTrackState

  /** Moving without a known end. */
  data object Indeterminate : ProgressTrackState

  /** Moving towards [fraction], from 0 to 1. */
  data class Determinate(val fraction: Float) : ProgressTrackState
}

/**
 * The 8 dp bar under a list row: a known progress, an indeterminate one, or an empty track. The
 * bar is the same height in every state, so the rows it sits in keep one height.
 */
@Composable
fun ProgressTrack(
    state: ProgressTrackState,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
  val semantics =
      if (description == null) Modifier
      else Modifier.semantics { contentDescription = description }
  val bar = modifier.fillMaxWidth().height(8.dp).clip(CircleShape).then(semantics)
  when (state) {
    ProgressTrackState.Empty ->
        Box(bar.background(MaterialTheme.taiga.segment))
    ProgressTrackState.Indeterminate ->
        LinearProgressIndicator(
            modifier = bar,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.taiga.segment,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    is ProgressTrackState.Determinate ->
        LinearProgressIndicator(
            progress = { state.fraction.coerceIn(0f, 1f) },
            modifier = bar,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.taiga.segment,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SegmentedProgressPreview() {
  PreviewTheme {
    SegmentedProgress(steps = 6, current = 3, description = "Step 3 of 6")
    ProgressTrack(ProgressTrackState.Determinate(0.42f), description = "Uploading 42%")
    ProgressTrack(ProgressTrackState.Indeterminate, description = "Analyzing")
    ProgressTrack(ProgressTrackState.Empty)
  }
}
