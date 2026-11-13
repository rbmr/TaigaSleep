package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
 * a [Pressable], so its touch target is 48 dp while the visible control is [SEGMENT_HEIGHT]. With
 * [fillWidth] the segments share the whole width, as in a settings row. A [floating] control casts a
 * shadow, for one that sits over scrolling content.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    floating: Boolean = false,
) {
  // The 48 dp box is the touch target; the track inside it is SEGMENT_HEIGHT tall and has no
  // padding of its own, so the selected segment (same height, same shape) covers it exactly.
  val shape = CircleShape
  Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
    Box(
        Modifier.matchParentSize()
            .padding(vertical = (48.dp - SEGMENT_HEIGHT) / 2)
            .then(if (floating) Modifier.shadow(6.dp, shape) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
    )
    Row(
        Modifier.fillMaxHeight().then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      options.forEachIndexed { index, option ->
        val selected = index == selectedIndex
        Pressable(
            onClick = { onSelect(index) },
            modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            shape = shape,
            role = Role.Tab,
            onClickLabel = option,
        ) {
          Box(
              modifier =
                  Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                      .height(SEGMENT_HEIGHT)
                      .background(
                          color =
                              if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                          shape = shape,
                      )
                      .padding(horizontal = MaterialTheme.taiga.space.m),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                text = option,
                style = MaterialTheme.taiga.text.meta,
                color =
                    if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
          }
        }
      }
    }
  }
}

/** The visible height of a [SegmentedControl]: track and selected segment alike. */
private val SEGMENT_HEIGHT = 40.dp

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
