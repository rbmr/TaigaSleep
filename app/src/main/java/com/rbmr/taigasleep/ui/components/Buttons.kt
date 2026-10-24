package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rbmr.taigasleep.ui.theme.taiga

/** Regular buttons sit inside cards and rows; large ones carry the main action of a screen. */
enum class ButtonSize(val minHeight: Int, val radius: Int) {
  Regular(minHeight = 52, radius = 16),
  Large(minHeight = 58, radius = 20),
}

@Composable
private fun ButtonSize.textStyle(): TextStyle =
    if (this == ButtonSize.Large) MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
    else MaterialTheme.typography.titleMedium

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
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier.fillMaxWidth().heightIn(min = size.minHeight.dp),
      shape = RoundedCornerShape(size.radius.dp),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = containerColor,
              contentColor = contentColor,
              disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
              disabledContentColor = MaterialTheme.taiga.disabled,
          ),
  ) {
    Text(text, style = size.textStyle(), textAlign = TextAlign.Center)
  }
}

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
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier.heightIn(min = size.minHeight.dp),
      shape = RoundedCornerShape(size.radius.dp),
      border = BorderStroke(1.dp, if (enabled) borderColor else MaterialTheme.colorScheme.outlineVariant),
      colors =
          ButtonDefaults.outlinedButtonColors(
              contentColor = contentColor,
              disabledContentColor = MaterialTheme.taiga.disabled,
          ),
  ) {
    Text(text, style = size.textStyle(), textAlign = TextAlign.Center)
  }
}

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

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  TextButton(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier,
      colors =
          ButtonDefaults.textButtonColors(
              contentColor = MaterialTheme.colorScheme.primary,
              disabledContentColor = MaterialTheme.taiga.disabled,
          ),
  ) {
    Text(text, style = MaterialTheme.typography.labelLarge)
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      SecondaryButton("Force stop", onClick = {}, modifier = Modifier.weight(1f))
      DestructiveButton("Discard", onClick = {}, modifier = Modifier.weight(1f))
    }
    TertiaryButton("Change", onClick = {})
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
