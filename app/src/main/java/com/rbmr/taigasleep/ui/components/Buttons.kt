package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.floor

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
    contentColor: Color = MaterialTheme.colorScheme.primary,
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
          color = if (enabled) contentColor else MaterialTheme.taiga.disabled,
      )
    }
  }
}

/**
 * A compact switch between a few views, such as Recordings | Trends. The chosen segment is filled
 * and exactly covers its part of the track: it has the track's height and the track's shape, so
 * no track shows around it and it sits flush with the outer edge at either end. Every segment is
 * a [Pressable], so its touch target is [TOUCH_TARGET] while the visible control starts at
 * [SEGMENT_HEIGHT]. Both are minimums: the track follows the tallest label, so a large system font
 * makes the control taller instead of cutting the label off. With [fillWidth] the segments share
 * the whole width, as in a settings row: a segment is never narrower than its label needs on one
 * line, the room that is left is split equally, and a label wraps only when the labels together do
 * not fit the row. Its labels get less room on each side than a control sized by its labels, so a
 * settings row fits four labels at a large text size on a narrow phone. A [floating] control
 * casts a shadow, for one that sits over scrolling content. A disabled control is dimmed and
 * ignores taps.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    floating: Boolean = false,
    enabled: Boolean = true,
) {
  // The box is the touch target and the track sits SEGMENT_INSET inside it. The row is as tall as
  // its tallest segment, and the track and the segments follow that height.
  val shape = CircleShape
  val space = MaterialTheme.taiga.space
  val labelPadding = if (fillWidth) space.s else space.m
  Box(
      modifier.heightIn(min = TOUCH_TARGET).then(if (enabled) Modifier else Modifier.alpha(0.38f)),
      contentAlignment = Alignment.Center,
  ) {
    Box(
        Modifier.matchParentSize()
            .padding(vertical = SEGMENT_INSET)
            .then(if (floating) Modifier.shadow(6.dp, shape) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
    )
    BoxWithConstraints(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
      val shares = if (fillWidth) segmentShares(options, maxWidth, labelPadding) else null
      Row(
          Modifier.heightIn(min = TOUCH_TARGET)
              .height(IntrinsicSize.Min)
              .then(if (shares != null) Modifier.fillMaxWidth() else Modifier),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        val total = shares?.sum() ?: 0f
        options.forEachIndexed { index, option ->
          val selected = index == selectedIndex
          val scheme = MaterialTheme.colorScheme
          val fill = if (selected) scheme.primary else Color.Transparent
          val ink = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
          Pressable(
              onClick = { onSelect(index) },
              modifier = shares?.let { Modifier.weight(it[index] / total) } ?: Modifier,
              shape = shape,
              enabled = enabled,
              role = Role.Tab,
              onClickLabel = option,
          ) {
            Box(
                modifier =
                    Modifier.then(if (shares != null) Modifier.fillMaxWidth() else Modifier)
                        .heightIn(min = SEGMENT_HEIGHT)
                        .fillMaxHeight()
                        .padding(vertical = SEGMENT_INSET)
                        .background(color = fill, shape = shape)
                        .padding(horizontal = labelPadding, vertical = space.xs),
                contentAlignment = Alignment.Center,
            ) {
              Text(
                  text = option,
                  style = MaterialTheme.taiga.text.meta,
                  color = ink,
                  textAlign = TextAlign.Center,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * How wide each segment of a full-width [SegmentedControl] is, in pixels: the room its label needs
 * on one line, and the room the labels leave in [width] split equally. A label that needs more than
 * an equal part is given what it needs and the other segments share what is left, so labels that
 * fit keep the same widths they had before and only a label that would wrap takes more room. When
 * the labels together need more than [width], every segment gets its label's width and the labels
 * wrap.
 */
@Composable
private fun segmentShares(labels: List<String>, width: Dp, labelPadding: Dp): List<Float> {
  val measurer = rememberTextMeasurer()
  val style = MaterialTheme.taiga.text.meta
  val density = LocalDensity.current
  val padding = with(density) { labelPadding.toPx() * 2 }
  val natural =
      remember(labels, style, padding, measurer) {
        labels.map { measurer.measure(text = it, style = style, maxLines = 1).size.width + padding }
      }
  val available = with(density) { width.toPx() }
  return remember(natural, available) { shareWidth(natural, available) }
}

/**
 * [widths] fitted into [available] pixels: each keeps its width, and what is left is split equally.
 * A width larger than an equal part is taken first, so the segments that fit stay equal. Every
 * share is a whole number of pixels rounded down, so a segment is never a pixel short of its label
 * once the row hands out what it could not divide.
 */
private fun shareWidth(widths: List<Float>, available: Float): List<Float> {
  val shares = MutableList(widths.size) { 0f }
  val fitting = widths.indices.toMutableSet()
  var left = available
  while (fitting.isNotEmpty()) {
    val each = left / fitting.size
    val wider = fitting.filter { widths[it] > each }
    if (wider.isEmpty()) {
      fitting.forEach { shares[it] = each }
      break
    }
    wider.forEach {
      shares[it] = widths[it]
      left -= widths[it]
      fitting.remove(it)
    }
  }
  return shares.map { floor(it) }
}

/** The touch target of a [SegmentedControl]. */
private val TOUCH_TARGET = 48.dp

/** The visible height of a [SegmentedControl] at the default font scale: track and segment alike. */
private val SEGMENT_HEIGHT = 40.dp

/** How far the track of a [SegmentedControl] sits inside its touch target. */
private val SEGMENT_INSET = (TOUCH_TARGET - SEGMENT_HEIGHT) / 2

/**
 * A full-width outlined control that names what is chosen on the left, with [emphasis] drawn in the
 * row weight, and the word that changes it on the right, such as "Analyzed with wav2sleep 1.2" and
 * "Change". Without an [action] the label is the whole control, as "Choose an analysis" is.
 */
@Composable
fun ChoiceButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: String? = null,
    emphasis: String? = null,
    enabled: Boolean = true,
) {
  val space = MaterialTheme.taiga.space
  val style = MaterialTheme.taiga.text.body
  val text =
      buildAnnotatedString {
        val start = emphasis?.let { label.indexOf(it) } ?: -1
        if (emphasis == null || start < 0) {
          append(label)
        } else {
          append(label.substring(0, start))
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(emphasis) }
          append(label.substring(start + emphasis.length))
        }
      }
  Pressable(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.taiga.shape.control,
      enabled = enabled,
      role = Role.Button,
      onClickLabel = label,
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .border(
                    width = 1.dp,
                    color =
                        if (enabled) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.taiga.shape.control,
                )
                .padding(horizontal = space.m, vertical = space.s),
        contentAlignment = Alignment.Center,
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = style,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.taiga.disabled,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
          Spacer(Modifier.width(space.s))
          Text(
              text = action,
              style = style,
              color =
                  if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.taiga.disabled,
              maxLines = 1,
          )
        }
      }
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
    ChoiceButton(
        label = "Analyzed with wav2sleep 1.2",
        action = "Change",
        emphasis = "wav2sleep 1.2",
        onClick = {},
    )
    ChoiceButton(label = "Choose an analysis", onClick = {})
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
    SegmentedControl(
        options = listOf("Recordings", "Trends"),
        selectedIndex = 1,
        onSelect = {},
    )
  }
}
