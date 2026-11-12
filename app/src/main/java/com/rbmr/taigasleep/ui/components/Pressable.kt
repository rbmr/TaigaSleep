package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The one clickable wrapper of the kit.
 *
 * The touch target is a rectangle of at least 48 dp that draws nothing. The press shows only on
 * the visible shape: the ripple is clipped to [shape] and the shape scales to 0.97 while the
 * finger is down. Every clickable row, pill, tile and button of the kit uses it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by
      animateFloatAsState(
          targetValue = if (pressed && enabled) 0.97f else 1f,
          animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
          label = "press",
      )
  Box(
      modifier =
          modifier
              .minimumInteractiveComponentSize()
              .combinedClickable(
                  interactionSource = interactionSource,
                  indication = null,
                  enabled = enabled,
                  role = role,
                  onClickLabel = onClickLabel,
                  onLongClickLabel = onLongClickLabel,
                  onLongClick = onLongClick,
                  onClick = onClick,
              ),
      contentAlignment = Alignment.Center,
  ) {
    Box(
        modifier = Modifier.scale(scale).clip(shape).indication(interactionSource, ripple()),
        content = content,
    )
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PressablePreview() {
  PreviewTheme {
    Pressable(onClick = {}, shape = CircleShape, role = Role.Button) {
      Box(
          modifier =
              Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                  .padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        Text(
            text = "Pressable",
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}
