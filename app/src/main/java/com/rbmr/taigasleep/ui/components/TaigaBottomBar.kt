package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class BottomBarItem(val label: String, val icon: ImageVector)

/**
 * The footer: 76 dp over the navigation inset, a hairline on top, the selected item as an icon in
 * an accent-soft pill with a bold accent label.
 */
@Composable
fun TaigaBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.navigationBarsPadding()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
          modifier = Modifier.fillMaxWidth().height(75.dp).padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.SpaceAround,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        items.forEachIndexed { index, item ->
          BottomBarButton(item, selected = index == selectedIndex) { onSelect(index) }
        }
      }
    }
  }
}

@Composable
private fun BottomBarButton(item: BottomBarItem, selected: Boolean, onClick: () -> Unit) {
  val color =
      if (selected) MaterialTheme.colorScheme.primary
      else MaterialTheme.colorScheme.onSurfaceVariant
  Column(
      modifier =
          Modifier.defaultMinSize(minWidth = 80.dp, minHeight = 56.dp)
              .selectable(selected = selected, role = Role.Tab, onClick = onClick),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
  ) {
    Row(
        modifier =
            Modifier.background(
                    color =
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
      Icon(
          imageVector = item.icon,
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(24.dp),
      )
    }
    Text(
        text = item.label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = if (selected) FontWeight.Bold else null,
    )
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TaigaBottomBarPreview() {
  PreviewTheme {
    TaigaBottomBar(
        items =
            listOf(
                BottomBarItem("History", Icons.Outlined.History),
                BottomBarItem("Home", Icons.Outlined.Home),
                BottomBarItem("Settings", Icons.Outlined.Tune),
            ),
        selectedIndex = 1,
        onSelect = {},
    )
  }
}
