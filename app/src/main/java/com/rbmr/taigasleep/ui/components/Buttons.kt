package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga

/** Regular buttons sit inside cards and rows; large ones carry the main action of a screen. */
enum class ButtonSize(val minHeight: Int) {
  Regular(minHeight = 52),
  Large(minHeight = 58),
}

@Composable
private fun ButtonSize.textStyle(): TextStyle =
    if (this == ButtonSize.Large) MaterialTheme.taiga.text.section
    else MaterialTheme.taiga.text.row

/**
 * The filled button; it carries the next step of a screen. The press is drawn by [Pressable], so the
 * touch target is a plain rectangle and only the control's own shape reacts.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Regular,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
  Pressable(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.taiga.shape.control,
      enabled = enabled,
      role = Role.Button,
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = size.minHeight.dp)
                .background(
                    color =
                        if (enabled) containerColor else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.taiga.shape.control,
                )
                .padding(horizontal = MaterialTheme.taiga.space.m),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          text = text,
          style = size.textStyle(),
          color = if (enabled) contentColor else MaterialTheme.taiga.disabled,
          textAlign = TextAlign.Center,
      )
    }
  }
}

/** The outlined button for the second action of a screen or a pair of equally weighted actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Regular,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Pressable(
      onClick = onClick,
      modifier = modifier,
      shape = MaterialTheme.taiga.shape.control,
      enabled = enabled,
      role = Role.Button,
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = size.minHeight.dp)
                .border(
                    width = 1.dp,
                    color = if (enabled) borderColor else MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.taiga.shape.control,
                )
                .padding(horizontal = MaterialTheme.taiga.space.m),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          text = text,
          style = size.textStyle(),
          color = if (enabled) contentColor else MaterialTheme.taiga.disabled,
          textAlign = TextAlign.Center,
      )
    }
  }
}

/** An outlined button that removes or ends something. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Regular,
) {
  SecondaryButton(
      text = text,
      onClick = onClick,
      modifier = modifier,
      enabled = enabled,
      size = size,
      borderColor = MaterialTheme.taiga.badBorder,
      contentColor = MaterialTheme.taiga.bad,
  )
}

/** A text button: the quiet action of a screen, such as Done on the finishing card. */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  Pressable(
      onClick = onClick,
      modifier = modifier,
      shape = MaterialTheme.taiga.shape.control,
      enabled = enabled,
      role = Role.Button,
  ) {
    Box(
        modifier =
            Modifier.heightIn(min = 48.dp)
                .padding(
                    horizontal = MaterialTheme.taiga.space.m,
                    vertical = MaterialTheme.taiga.space.s,
                ),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          text = text,
          style = MaterialTheme.taiga.text.row,
          color =
              if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.taiga.disabled,
      )
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ButtonsPreview() {
  PreviewTheme {
    PrimaryButton("Start recording", onClick = {}, size = ButtonSize.Large)
    PrimaryButton("Start recording", onClick = {}, size = ButtonSize.Large, enabled = false)
    PrimaryButton("Next", onClick = {})
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SecondaryButton("Force stop", onClick = {}, modifier = Modifier.weight(1f))
      DestructiveButton("Discard", onClick = {}, modifier = Modifier.weight(1f))
    }
    TertiaryButton("Change", onClick = {})
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PrimaryButton(
          text = "Fix in settings",
          onClick = {},
          modifier = Modifier.weight(1f),
          containerColor = MaterialTheme.taiga.warn,
          contentColor = MaterialTheme.taiga.onWarn,
      )
      SecondaryButton(
          text = "Setup guide",
          onClick = {},
          modifier = Modifier.weight(1f),
          borderColor = MaterialTheme.taiga.warnBorder,
      )
    }
  }
}
