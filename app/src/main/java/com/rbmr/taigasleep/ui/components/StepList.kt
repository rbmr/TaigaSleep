package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/** What one step of a sequence is doing. */
enum class StepState {
  Done,
  Active,
  Waiting,
}

/** The optional line under the current step. */
sealed interface StepProgress {
  data object Indeterminate : StepProgress

  data class Determinate(val fraction: Float) : StepProgress
}

/** One row of a [StepList]. */
data class StepItem(
    val label: String,
    val state: StepState,
    val detail: String? = null,
    val progress: StepProgress? = null,
)

/**
 * A sequence of steps: done (a filled circle with a check), in progress (a spinner) and waiting
 * (an empty ring with a secondary label). The step that carries [StepItem.progress] draws its
 * progress line underneath.
 */
@Composable
fun StepList(steps: List<StepItem>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    steps.forEach { step ->
      Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.taiga.space.s),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        StepMarker(step.state)
        Spacer(Modifier.width(MaterialTheme.taiga.space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
              text = step.label,
              style = MaterialTheme.taiga.text.row,
              color =
                  if (step.state == StepState.Waiting) MaterialTheme.colorScheme.onSurfaceVariant
                  else MaterialTheme.colorScheme.onSurface,
          )
          step.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.taiga.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      step.progress?.let { progress ->
        val line =
            Modifier.fillMaxWidth()
                .padding(start = 40.dp)
                .height(4.dp)
                .clip(CircleShape)
        when (progress) {
          StepProgress.Indeterminate ->
              LinearProgressIndicator(
                  modifier = line,
                  color = MaterialTheme.colorScheme.primary,
                  trackColor = MaterialTheme.taiga.segment,
              )
          is StepProgress.Determinate ->
              LinearProgressIndicator(
                  progress = { progress.fraction },
                  modifier = line,
                  color = MaterialTheme.colorScheme.primary,
                  trackColor = MaterialTheme.taiga.segment,
              )
        }
      }
    }
  }
}

@Composable
private fun StepMarker(state: StepState) {
  Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
    when (state) {
      StepState.Done ->
          Box(
              modifier =
                  Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
              contentAlignment = Alignment.Center,
          ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
          }
      StepState.Active ->
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
      StepState.Waiting ->
          Box(
              modifier =
                  Modifier.size(20.dp)
                      .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
          )
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StepListPreview() {
  PreviewTheme {
    StepList(
        steps =
            listOf(
                StepItem("Stopping the tracker", StepState.Done),
                StepItem(
                    label = "Fetching from the tracker",
                    state = StepState.Active,
                    detail = "Entry 2 of 3 · 41 233 samples stored",
                    progress = StepProgress.Indeterminate,
                ),
                StepItem("Saving on this phone", StepState.Waiting),
            )
    )
  }
}
