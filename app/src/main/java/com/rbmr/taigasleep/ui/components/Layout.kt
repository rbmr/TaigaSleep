package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * One screen of the app: the system insets, an optional top bar with Back and actions, a large
 * title, and a scrolling padded column for the content.
 */
@Composable
fun TaigaScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
  val space = MaterialTheme.taiga.space
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      if (onBack != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Pressable(onClick = onBack, shape = CircleShape, role = Role.Button) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              Icon(
                  imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                  contentDescription = stringResource(R.string.action_back),
              )
            }
          }
          Spacer(Modifier.weight(1f))
          if (actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
          }
        }
      }
      val scrolling =
          if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
      Column(
          modifier =
              Modifier.weight(1f)
                  .fillMaxWidth()
                  .then(scrolling)
                  .navigationBarsPadding()
                  .padding(horizontal = space.m)
                  .padding(bottom = space.l),
          verticalArrangement = Arrangement.spacedBy(space.m),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.taiga.text.screenTitle)
            if (subtitle != null) {
              Text(
                  text = subtitle,
                  style = MaterialTheme.taiga.text.meta,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          if (onBack == null && actions != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
          }
        }
        content()
      }
    }
  }
}

/**
 * A titled group of content. The optional [info] adds the ⓘ button that opens the explanation in
 * an [InfoSheet]; the optional [trailing] is an action that belongs to the whole group.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    info: InfoContent? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
  var showInfo by remember { mutableStateOf(false) }
  Column(
      modifier = modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = title,
          style = MaterialTheme.taiga.text.section,
          modifier = Modifier.weight(1f),
      )
      if (info != null) {
        Pressable(
            onClick = { showInfo = true },
            shape = CircleShape,
            role = Role.Button,
            onClickLabel = info.title,
        ) {
          Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.action_explain),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      trailing?.invoke()
    }
    content()
  }
  if (showInfo && info != null) {
    InfoSheet(info = info, onDismiss = { showInfo = false })
  }
}

/** What sits at the end of a [ListRow]. */
sealed interface ListRowTrailing {
  data object None : ListRowTrailing

  data object Chevron : ListRowTrailing

  data class Switch(val checked: Boolean) : ListRowTrailing

  data class Radio(val selected: Boolean) : ListRowTrailing

  data class Pill(val label: String, val state: StatusPillState) : ListRowTrailing
}

/**
 * One row of a list: an optional leading icon, the title, an optional value and the trailing slot.
 * The row is a button when [onClick] is given; the switch and radio are drawn from their state and
 * the row's click changes them.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    trailing: ListRowTrailing = ListRowTrailing.Chevron,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
  val space = MaterialTheme.taiga.space
  val row: @Composable () -> Unit = {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = space.m, vertical = space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(space.m))
      }
      Text(
          text = title,
          style = MaterialTheme.taiga.text.row,
          color =
              if (enabled) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.taiga.disabled,
          modifier = Modifier.weight(1f),
      )
      if (value != null) {
        Spacer(Modifier.width(space.s))
        Text(
            text = value,
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (trailing != ListRowTrailing.None) {
        Spacer(Modifier.width(space.s))
        when (trailing) {
          ListRowTrailing.Chevron ->
              Icon(
                  imageVector = Icons.Outlined.ChevronRight,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          is ListRowTrailing.Switch ->
              Switch(checked = trailing.checked, onCheckedChange = null, enabled = enabled)
          is ListRowTrailing.Radio ->
              RadioButton(selected = trailing.selected, onClick = null, enabled = enabled)
          is ListRowTrailing.Pill -> StatusPill(state = trailing.state, label = trailing.label)
          ListRowTrailing.None -> Unit
        }
      }
    }
  }
  if (onClick != null) {
    Pressable(
        onClick = onClick,
        modifier = modifier,
        shape = RectangleShape,
        enabled = enabled,
        role = Role.Button,
        onClickLabel = onClickLabel,
        onLongClick = onLongClick,
    ) {
      row()
    }
  } else {
    Box(modifier) { row() }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SectionPreview() {
  PreviewTheme {
    Section(
        title = "Data quality",
        info = InfoContent("Data quality", "How much of the recording the analysis could use."),
        trailing = { StatusPill(StatusPillState.Ok, "86 / 100") },
    ) {
      Text(
          text = "The score describes the recording, not how well you slept.",
          style = MaterialTheme.taiga.text.body,
      )
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ListRowPreview() {
  PreviewTheme {
    ListRow(title = "Tracker", value = "Polar H10", onClick = {})
    ListRow(
        title = "Compress uploads",
        trailing = ListRowTrailing.Switch(checked = true),
        onClick = {},
    )
    ListRow(title = "Raw data", trailing = ListRowTrailing.Radio(selected = true), onClick = {})
    ListRow(
        title = "Uploads",
        value = "Automatic",
        trailing = ListRowTrailing.Pill("On", StatusPillState.Ok),
        onClick = {},
    )
    ListRow(title = "Access tokens", value = "2", enabled = false)
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TaigaScreenPreview() {
  PreviewTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      TaigaScreen(title = "Settings", onBack = {}) {
        Section(title = "Server", info = InfoContent("Server", "The address Taiga uploads to.")) {
          ListRow(title = "Uploads", value = "Automatic", onClick = {})
          ListRow(title = "Analysis", value = "Manual", onClick = {})
        }
        Section(title = "App") {
          ListRow(
              title = "Compress uploads",
              trailing = ListRowTrailing.Switch(checked = true),
              onClick = {},
          )
          ListRow(
              title = "Raw data",
              trailing = ListRowTrailing.Radio(selected = true),
              onClick = {},
          )
          ListRow(
              title = "Appearance",
              trailing = ListRowTrailing.Pill("System", StatusPillState.Neutral),
              onClick = {},
          )
        }
      }
    }
  }
}
