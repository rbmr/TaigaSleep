package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/** The five states a pill can report. */
enum class StatusPillState {
  Ok,
  Warn,
  Bad,
  Busy,
  Neutral,
}

private val StatusPillState.icon: ImageVector?
  get() =
      when (this) {
        StatusPillState.Ok -> Icons.Outlined.CheckCircle
        StatusPillState.Warn -> Icons.Outlined.WarningAmber
        StatusPillState.Bad -> Icons.Outlined.ErrorOutline
        StatusPillState.Busy,
        StatusPillState.Neutral -> null
      }

/**
 * One state as a small pill: ok, warn, bad, busy or neutral. Without an [onClick] it is a fixed
 * fact that only states itself; with one it is a button drawn through [Pressable].
 */
@Composable
fun StatusPill(
    state: StatusPillState,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
  val colors = MaterialTheme.taiga
  val scheme = MaterialTheme.colorScheme
  val container =
      when (state) {
        StatusPillState.Ok -> colors.okContainer
        StatusPillState.Warn -> colors.warnContainer
        StatusPillState.Bad -> colors.badContainer
        StatusPillState.Busy -> scheme.primaryContainer
        StatusPillState.Neutral -> scheme.surfaceVariant
      }
  val ink =
      when (state) {
        StatusPillState.Ok -> colors.ok
        StatusPillState.Warn -> colors.warn
        StatusPillState.Bad -> colors.bad
        StatusPillState.Busy -> scheme.primary
        StatusPillState.Neutral -> scheme.onSurfaceVariant
      }
  val pill: @Composable () -> Unit = {
    Surface(shape = CircleShape, color = container, contentColor = ink) {
      Row(
          modifier = Modifier.heightIn(min = 24.dp).padding(horizontal = 10.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (state == StatusPillState.Busy) {
          CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              color = ink,
              strokeWidth = 1.5.dp,
          )
        } else {
          state.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp)) }
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
      }
    }
  }
  if (onClick != null) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        role = Role.Button,
        onClickLabel = label,
    ) {
      pill()
    }
  } else {
    Box(modifier) { pill() }
  }
}

/**
 * A chip that chooses one of a few values, such as a trend range. The chosen chip is filled; the
 * others sit on the quiet track colour. It is a [Pressable], so its touch target is 48 dp.
 */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Pressable(
      onClick = onClick,
      modifier = modifier,
      shape = CircleShape,
      role = Role.RadioButton,
      onClickLabel = label,
  ) {
    Box(
        modifier =
            Modifier.height(32.dp)
                .background(
                    color =
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                )
                .padding(horizontal = MaterialTheme.taiga.space.m),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          text = label,
          style = MaterialTheme.taiga.text.meta,
          color =
              if (selected) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
      )
    }
  }
}

/**
 * A warn pill that says something qualifies a figure and carries the ⓘ that explains it. The pill
 * stays small while the touch target is the 48 dp a button needs.
 */
@Composable
fun InfoPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Pressable(
      onClick = onClick,
      modifier = modifier,
      shape = CircleShape,
      role = Role.Button,
      onClickLabel = label,
  ) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.taiga.warnContainer,
        contentColor = MaterialTheme.taiga.warnInk,
    ) {
      Row(
          modifier =
              Modifier.heightIn(min = 24.dp)
                  .padding(
                      start = 10.dp,
                      end = MaterialTheme.taiga.space.s,
                      top = MaterialTheme.taiga.space.xs,
                      bottom = MaterialTheme.taiga.space.xs,
                  ),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.xs),
      ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
      }
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusPillPreview() {
  PreviewTheme {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusPill(StatusPillState.Ok, "Connected")
      StatusPill(StatusPillState.Warn, "Needed")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusPill(StatusPillState.Bad, "Upload failed")
      StatusPill(StatusPillState.Busy, "Uploading 42%")
      StatusPill(StatusPillState.Neutral, "Not set")
    }
    StatusPill(StatusPillState.Neutral, "Open", onClick = {})
    InfoPill(label = "Experimental", onClick = {})
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ChoiceChip(label = "7 days", selected = true, onClick = {})
      ChoiceChip(label = "14 days", selected = false, onClick = {})
      ChoiceChip(label = "30 days", selected = false, onClick = {})
    }
  }
}
