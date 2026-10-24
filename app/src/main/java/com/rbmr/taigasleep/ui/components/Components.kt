package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rbmr.taigasleep.ui.theme.taiga

@Composable
fun ScreenHeader(
    kicker: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    kickerColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Column(modifier) {
    Text(
        text = kicker,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.16.sp),
        color = kickerColor,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = title, style = MaterialTheme.typography.displayLarge)
    if (subtitle != null) {
      Spacer(Modifier.height(8.dp))
      Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
      text = text.uppercase(),
      style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.68.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier.padding(horizontal = 6.dp),
  )
}

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
      shape = RoundedCornerShape(24.dp),
      color = containerColor,
      border = BorderStroke(1.dp, borderColor),
  ) {
    Column(modifier = Modifier.padding(contentPadding), content = content)
  }
}

/** A label above a card whose rows are separated by hairlines. */
@Composable
fun SettingsGroup(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier) {
    SectionLabel(label, Modifier.padding(bottom = 8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = { Column(content = content) },
    )
  }
}

@Composable
fun SettingsDivider() {
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 56.dp)
              .combinedClickable(onClick = onClick, onLongClick = onLongClick)
              .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = subtitleColor,
          modifier = Modifier.padding(top = 2.dp),
      )
    }
    Icon(
        imageVector = Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
  }
}

/** The round accent-soft badge that carries an icon at the start of a card row. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier) {
  Box(
      modifier =
          modifier
              .size(44.dp)
              .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
    )
  }
}

/** A small label over one value, for the live readings of a recording. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    caption: String? = null,
) {
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(
          text = label.uppercase(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          text = value,
          style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
          color = valueColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      if (caption != null) {
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComponentsPreview() {
  PreviewTheme {
    ScreenHeader(kicker = "TONIGHT", title = "Sleep well.", subtitle = "Connect your sensor.")
    TaigaCard {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(Icons.Outlined.Sensors)
        Spacer(Modifier.width(14.dp))
        Text("Polar H10", style = MaterialTheme.typography.titleLarge)
      }
    }
    SettingsGroup("Recording") {
      SettingsRow("Preventing shutdown", "Ready", subtitleColor = MaterialTheme.taiga.ok)
      SettingsDivider()
      SettingsRow("Remote", "Not connected", subtitleColor = MaterialTheme.taiga.warn)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StatTile("ECG", "130 Hz", Modifier.weight(1f))
      StatTile("Sensor", "Connected", Modifier.weight(1f), valueColor = MaterialTheme.colorScheme.primary)
    }
  }
}
