package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.TaigaColors
import com.rbmr.taigasleep.ui.theme.taiga

enum class StatusKind {
  Ok,
  Warn,
  Bad,
}

private val StatusKind.icon
  get() =
      when (this) {
        StatusKind.Ok -> Icons.Outlined.CheckCircle
        StatusKind.Warn -> Icons.Outlined.WarningAmber
        StatusKind.Bad -> Icons.Outlined.ErrorOutline
      }

private fun StatusKind.color(colors: TaigaColors) =
    when (this) {
      StatusKind.Ok -> colors.ok
      StatusKind.Warn -> colors.warn
      StatusKind.Bad -> colors.bad
    }

private fun StatusKind.container(colors: TaigaColors) =
    when (this) {
      StatusKind.Ok -> colors.okContainer
      StatusKind.Warn -> colors.warnContainer
      StatusKind.Bad -> colors.badContainer
    }

/**
 * A message about the state of the phone or the sensor. The ok banner is quiet and one color; warn
 * and bad ones have a border, plain text and optional [actions] (buttons that take `weight(1f)`).
 */
@Composable
fun StatusBanner(
    kind: StatusKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    body: String? = null,
    details: List<String> = emptyList(),
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = MaterialTheme.taiga
  val ink = MaterialTheme.colorScheme.onSurface
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  val quiet = kind == StatusKind.Ok
  val border =
      when (kind) {
        StatusKind.Ok -> null
        StatusKind.Warn -> BorderStroke(1.dp, colors.warnBorder)
        StatusKind.Bad -> BorderStroke(1.dp, colors.badBorder)
      }
  Surface(
      modifier = modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      color = kind.container(colors),
      border = border,
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), Arrangement.spacedBy(12.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = kind.color(colors),
            modifier = Modifier.padding(top = 1.dp).size(24.dp),
        )
        Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
          if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (quiet) kind.color(colors) else ink,
            )
          }
          if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (quiet) kind.color(colors) else if (title == null) ink else muted,
            )
          }
          details.forEach {
            Text(text = "• $it", style = MaterialTheme.typography.bodyMedium, color = ink)
          }
        }
      }
      if (actions != null) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), content = actions)
      }
    }
  }
}

/** A small pill with the state of one check. */
@Composable
fun StatusChip(kind: StatusKind, label: String, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.taiga
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(50),
      color = kind.container(colors),
      contentColor = kind.color(colors),
  ) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(kind.icon, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
  }
}

/**
 * A toggle shown as a pill. Without an [onClick] the chip is a fixed fact and is drawn solid, like
 * the heart rate that is always recorded.
 */
@Composable
fun TaigaChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val (container, content) =
      when {
        onClick == null -> scheme.primary to scheme.onPrimary
        selected -> scheme.primaryContainer to scheme.primary
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
      }
  val toggle =
      if (onClick != null) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
      } else {
        Modifier
      }
  Surface(modifier = modifier.then(toggle), shape = RoundedCornerShape(50), color = container) {
    Row(
        modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusPreview() {
  PreviewTheme {
    StatusBanner(
        kind = StatusKind.Ok,
        title = "Ready for the night",
        body = "Background access allowed",
    )
    StatusBanner(
        kind = StatusKind.Warn,
        title = "Background settings need fixing",
        body = "Without these the phone can stop the recording.",
        details = listOf("Allow Taiga to run in the background."),
        actions = {
          PrimaryButton(
              text = "Allow background",
              onClick = {},
              modifier = Modifier.weight(1f),
              containerColor = MaterialTheme.taiga.warn,
              contentColor = MaterialTheme.taiga.onWarn,
          )
          SecondaryButton(
              text = "Open settings",
              onClick = {},
              modifier = Modifier.weight(1f),
              borderColor = MaterialTheme.taiga.warnBorder,
          )
        },
    )
    StatusBanner(kind = StatusKind.Bad, title = "Writing to the phone failed")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusChip(StatusKind.Ok, "OK")
      StatusChip(StatusKind.Warn, "Check")
      StatusChip(StatusKind.Bad, "Not yet")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TaigaChip("Heart rate", selected = true)
      TaigaChip("ECG · 130 Hz", selected = true, onClick = {})
      TaigaChip("Motion", selected = false, onClick = {})
    }
  }
}
