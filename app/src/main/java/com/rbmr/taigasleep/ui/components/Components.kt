package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * A card: the surface a group of content or rows sits on. Rows that need no gaps between them pass
 * a [contentPadding] of zero and separate themselves with [SettingsDivider].
 */
@Composable
fun TaigaCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    contentPadding: PaddingValues = PaddingValues(MaterialTheme.taiga.space.m),
    content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.taiga.shape.card,
      color = containerColor,
      border = BorderStroke(1.dp, borderColor),
  ) {
    Column(modifier = Modifier.padding(contentPadding), content = content)
  }
}

/** The hairline between two rows of the same card. */
@Composable
fun SettingsDivider() {
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Where one row sits in a card that a lazy list draws from its rows. */
enum class CardRowPosition {
  FIRST,
  MIDDLE,
  LAST,
  ONLY,
}

/**
 * One row of a card that a lazy list draws as its own item. A card cannot wrap items of a lazy
 * list, so each row paints the card's surface and the border of the edges it owns: the first its
 * rounded top, the last its rounded bottom, and a row that has one above it the hairline between
 * them. Together the rows read as the one card they are, and only visible rows exist.
 */
@Composable
fun CardRow(
    position: CardRowPosition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
  val radius = MaterialTheme.taiga.shape.cardRadius
  val top = position == CardRowPosition.FIRST || position == CardRowPosition.ONLY
  val bottom = position == CardRowPosition.LAST || position == CardRowPosition.ONLY
  val outline = MaterialTheme.colorScheme.outlineVariant
  val radiusPx = with(LocalDensity.current) { radius.toPx() }
  Box(
      modifier
          .fillMaxWidth()
          .clip(cardRowShape(radius, top, bottom))
          .background(MaterialTheme.colorScheme.surface)
          .drawBehind { drawCardRow(outline, radiusPx, top, bottom) }
  ) {
    // The hairline takes a line of its own above the row, as the divider it replaces did.
    Box(Modifier.padding(top = if (top) 0.dp else DividerThickness)) { content() }
  }
}

/** The thickness of the hairline between two rows, and of the card's own border. */
private val DividerThickness = 1.dp

/** The shape of one row of a card: rounded only where the card itself ends. */
private fun cardRowShape(radius: Dp, top: Boolean, bottom: Boolean) =
    RoundedCornerShape(
        topStart = if (top) radius else 0.dp,
        topEnd = if (top) radius else 0.dp,
        bottomEnd = if (bottom) radius else 0.dp,
        bottomStart = if (bottom) radius else 0.dp,
    )

/**
 * The border of one row of a card: the two sides, the rounded end where the card ends, and the
 * hairline above a row that has one above it. A joined edge draws nothing, so two rows never
 * paint two lines where they meet.
 */
private fun DrawScope.drawCardRow(outline: Color, radius: Float, top: Boolean, bottom: Boolean) {
  val stroke = DividerThickness.toPx()
  val inset = stroke / 2f
  val corner = (radius - inset).coerceAtLeast(0f)
  val left = inset
  val right = size.width - inset
  val topY = inset
  val bottomY = size.height - inset
  val sideTop = if (top) topY + corner else topY
  val sideBottom = if (bottom) bottomY - corner else bottomY
  drawLine(outline, Offset(left, sideTop), Offset(left, sideBottom), stroke)
  drawLine(outline, Offset(right, sideTop), Offset(right, sideBottom), stroke)
  if (top) {
    val path =
        Path().apply {
          moveTo(left, topY + corner)
          arcTo(Rect(left, topY, left + 2 * corner, topY + 2 * corner), 180f, 90f, false)
          lineTo(right - corner, topY)
          arcTo(Rect(right - 2 * corner, topY, right, topY + 2 * corner), 270f, 90f, false)
        }
    drawPath(path, outline, style = Stroke(stroke))
  } else {
    drawLine(outline, Offset(left, topY), Offset(right, topY), stroke)
  }
  if (bottom) {
    val path =
        Path().apply {
          moveTo(right, bottomY - corner)
          arcTo(Rect(right - 2 * corner, bottomY - 2 * corner, right, bottomY), 0f, 90f, false)
          lineTo(left + corner, bottomY)
          arcTo(Rect(left, bottomY - 2 * corner, left + 2 * corner, bottomY), 90f, 90f, false)
        }
    drawPath(path, outline, style = Stroke(stroke))
  }
}

/**
 * The one switch of the app. Off reads as a working switch that is off: an outlined track with a
 * visible border and a mid-grey thumb. A disabled switch keeps the faded Material colours, so the
 * two never look alike. The theme's own outline is a pale divider tone, so the off colours come
 * from onSurfaceVariant instead.
 */
@Composable
fun TaigaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  val off = MaterialTheme.colorScheme.onSurfaceVariant
  Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = modifier,
      enabled = enabled,
      colors =
          SwitchDefaults.colors(
              uncheckedThumbColor = off,
              uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
              uncheckedBorderColor = off,
          ),
  )
}

/** The Remove action of a [TimePickerDialog], for a value that can be cleared. */
data class TimePickerClear(val label: String, val onClick: () -> Unit, val enabled: Boolean = true)

/** The words and the optional Remove action of a [TimePickerDialog]. */
data class TimePickerText(
    val title: String,
    val confirm: String,
    val clear: TimePickerClear? = null,
)

/**
 * Picks a time of day, in minutes since midnight, and uses the clock the caller chose with
 * [is24Hour]. The picker opens at the [minuteOfDay] it was given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    minuteOfDay: Int,
    is24Hour: Boolean,
    text: TimePickerText,
    onConfirm: (minuteOfDay: Int) -> Unit,
    onDismiss: () -> Unit,
) {
  val state =
      rememberTimePickerState(
          initialHour = minuteOfDay / 60,
          initialMinute = minuteOfDay % 60,
          is24Hour = is24Hour,
      )
  Dialog(onDismissRequest = onDismiss) {
    Surface(
        shape = MaterialTheme.taiga.shape.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
      Column(
          modifier =
              Modifier.padding(MaterialTheme.taiga.space.l).verticalScroll(rememberScrollState()),
      ) {
        Text(text = text.title, style = MaterialTheme.taiga.text.section)
        Spacer(Modifier.height(MaterialTheme.taiga.space.m))
        TimePicker(state = state)
        Spacer(Modifier.height(MaterialTheme.taiga.space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
          val clear = text.clear
          if (clear != null) {
            TextButton(
                onClick = {
                  clear.onClick()
                  onDismiss()
                },
                enabled = clear.enabled,
            ) {
              Text(clear.label)
            }
          }
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
          TextButton(
              onClick = {
                onConfirm(state.hour * 60 + state.minute)
                onDismiss()
              }
          ) {
            Text(text.confirm)
          }
        }
      }
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TaigaCardPreview() {
  PreviewTheme {
    TaigaCard {
      Text("A card holds a group", style = MaterialTheme.taiga.text.body)
      SettingsDivider()
      Text("Rows are separated by hairlines", style = MaterialTheme.taiga.text.meta)
    }
  }
}
