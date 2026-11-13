package com.rbmr.taigasleep.ui.components

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import com.rbmr.taigasleep.ui.theme.taiga
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How tall a den header is, and how much of it the ground takes below the horizon. */
enum class DenSize(val height: Dp, val ground: Dp) {
  /** The full header of Home before a recording. */
  Header(height = 264.dp, ground = 14.dp),
  /** The smaller header while a recording runs and while it is finished. */
  Compact(height = 180.dp, ground = 12.dp),
}

/** The widest the arch's own view may be drawn, so sky remains beside it on a phone screen. */
private val DenWidth = 260.dp

/** What a tap on the den does, if anything. */
enum class DenTapEffect {
  None,

  /** The bear wakes: his eyes open, he blinks twice and settles back to sleep. */
  Wake,

  /** The den's force field: a tint, a sweeping teal band and a ring, one set per tap. */
  ForceField,
}

/**
 * The bear in its den, as the home header: the logo's arch standing on a horizon that runs across
 * the whole width, a faint aurora inside the arch and the bear resting on the den floor. The arch
 * stays narrow enough to leave sky beside it, where a few small stars sit in the dark theme. The
 * bear and arch paths are the logo's own.
 *
 * The header draws nothing else: its ground is the colour the content sits on, so a screen places it
 * above its column and lets the den end where the content begins.
 */
@Composable
fun BearDen(
    modifier: Modifier = Modifier,
    size: DenSize = DenSize.Header,
    variant: LogoVariant = LogoVariant.Sleeping,
    glow: Boolean = false,
    tapEffect: DenTapEffect = DenTapEffect.None,
    contentDescription: String? = null,
) {
  val colors = MaterialTheme.taiga
  val scheme = MaterialTheme.colorScheme
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  // The developer setting that turns animations off: then the eyes only open and close.
  val animations = remember { ValueAnimator.areAnimatorsEnabled() }
  val statusBarTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this) }

  // The wake plays on one 2.8 s timeline: the open eyes are squashed and released along it, and
  // the bear's outline never changes.
  val wakeTime = remember { Animatable(0f) }
  var waking by remember { mutableStateOf(false) }
  val pulses = remember { mutableStateListOf<DenPulse>() }
  // The sleeping bear's floating z's loop on one clock; the second one trails the first.
  val zClock =
      rememberInfiniteTransition(label = "z")
          .animateFloat(
              initialValue = 0f,
              targetValue = 1f,
              animationSpec = infiniteRepeatable(tween(Z_LOOP_MS, easing = LinearEasing)),
              label = "z",
          )

  suspend fun wake() {
    try {
      if (animations) {
        wakeTime.snapTo(0f)
        wakeTime.animateTo(1f, tween(WAKE_MS, easing = LinearEasing))
      } else {
        // Animations are off: the eyes only show open for a moment.
        wakeTime.snapTo(HOLD_OPEN)
        delay(WAKE_STATIC_MS)
      }
    } finally {
      waking = false
    }
  }

  val tap =
      when (tapEffect) {
        DenTapEffect.None -> Modifier
        DenTapEffect.Wake ->
            Modifier.pointerInput(variant, animations) {
              detectTapGestures {
                // One wake at a time: a tap during the animation is ignored.
                if (waking) return@detectTapGestures
                waking = true
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { wake() }
              }
            }
        DenTapEffect.ForceField ->
            Modifier.pointerInput(animations) {
              detectTapGestures { at ->
                if (!animations) return@detectTapGestures
                val pulse = DenPulse(at)
                pulses.add(pulse)
                scope.launch {
                  pulse.progress.animateTo(1f, tween(PULSE_MS, easing = LinearEasing))
                  pulses.remove(pulse)
                }
              }
            }
      }

  val described =
      if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
      } else {
        Modifier
      }

  Canvas(modifier.fillMaxWidth().height(size.height).then(tap).then(described)) {
    val side = 24.dp.toPx()
    val ground = size.ground.toPx()
    // The horizon sits near the bottom of the header: only a thin strip of ground lies below it.
    val horizon = this.size.height - ground
    val sky = (horizon - statusBarTop).coerceAtLeast(1f)
    // The arch keeps the logo's proportions, is never wider than DenWidth and always fits between
    // the status bar and the horizon, so sky stays visible beside and above it.
    val unit =
        minOf(
            DenWidth.toPx() / VIEW_WIDTH,
            (this.size.width - 2 * side) / VIEW_WIDTH,
            sky / VIEW_HEIGHT,
        )
    val left = (this.size.width - VIEW_WIDTH * unit) / 2f
    // The arch stands on the horizon, so its feet meet the ground at the end of the header.
    val top = horizon - ARCH_BOTTOM * unit

    // Sky above the horizon, ground below it. The ground is the colour the content sits on.
    drawRect(color = scheme.background, size = Size(this.size.width, horizon))
    if (colors.dark) drawStars(this.size.width, statusBarTop.toFloat(), sky, scheme.onBackground)
    drawRect(
        color = scheme.surface,
        topLeft = Offset(0f, horizon),
        size = Size(this.size.width, this.size.height - horizon),
    )
    // The horizon itself carries the arch's colours and fades out towards both screen edges.
    drawLine(
        brush =
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to colors.auroraStart,
                0.65f to colors.auroraEnd,
                1f to Color.Transparent,
            ),
        start = Offset(0f, horizon),
        end = Offset(this.size.width, horizon),
        strokeWidth = 1.5.dp.toPx(),
    )

    translate(left, top) {
      scale(unit, pivot = Offset.Zero) {
        // A faint aurora lives inside the arch, and a recording adds the lighter den glow.
        clipPath(archInterior) {
          drawRect(
              brush =
                  Brush.radialGradient(
                      colors =
                          listOf(
                              colors.auroraStart.copy(alpha = 0.16f),
                              colors.auroraEnd.copy(alpha = 0.06f),
                              Color.Transparent,
                          ),
                      center = Offset(VIEW_WIDTH / 2f, 84f),
                      radius = 96f,
                  ),
              topLeft = Offset(ARCH_LEFT, ARCH_TOP),
              size = Size(ARCH_RIGHT - ARCH_LEFT, ARCH_BOTTOM - ARCH_TOP),
          )
        }
        if (glow) {
          val center = Offset(VIEW_WIDTH / 2f, ARCH_BOTTOM - 4f)
          scale(scaleX = 1f, scaleY = GLOW_FLATTENING, pivot = center) {
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(colors.auroraStart.copy(alpha = 0.26f), Color.Transparent),
                        center = center,
                        radius = VIEW_WIDTH * 0.72f,
                    ),
                radius = VIEW_WIDTH * 0.72f,
                center = center,
            )
          }
        }
        // Every running tap tints the den once more; the tints stack while they fade.
        pulses.forEach { pulse -> drawTint(pulse, colors.auroraStart, colors.auroraEnd) }

        // The arch's own gradient, exactly as the logo draws it: solid, feet included.
        drawPath(
            path = archPath,
            brush =
                Brush.linearGradient(
                    colors = listOf(colors.auroraStart, colors.auroraEnd),
                    start = Offset(ARCH_LEFT, ARCH_BOTTOM),
                    end = Offset(ARCH_RIGHT, ARCH_TOP),
                ),
            style = Stroke(width = ARCH_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // The tap animation only moves the eyes: the outline and the ink never change.
        val body =
            when {
              waking -> wakingBear(eyeScaleY(wakeTime.value, animations))
              variant == LogoVariant.Awake -> awakeBearPath
              else -> sleepingBearPath
            }
        drawPath(path = body, color = colors.bear)
        // The z's float only while he sleeps: the eyes are open for the whole wake animation.
        if (!waking && variant != LogoVariant.Awake) {
          drawSleepZ(zClock.value, animations, colors.auroraEnd)
        }

        // The band is light inside the arch; the ring grows freely over the whole header.
        clipPath(archInterior) {
          pulses.forEach { pulse -> drawBand(pulse, colors.auroraStart) }
        }
        pulses.forEach { pulse ->
          val at = Offset((pulse.at.x - left) / unit, (pulse.at.y - top) / unit)
          drawRing(pulse, at, colors.auroraStart, colors.auroraEnd)
        }
      }
    }
  }
}

/** One tap of the force field: the tint, the band and the ring share one timeline. */
private class DenPulse(val at: Offset) {
  val progress = Animatable(0f)
}

// The wake: 2.8 s on a linear time base, the eyes' vertical scale eased between these keyframes.
private const val WAKE_MS = 2_800
private const val WAKE_STATIC_MS = 1_000L
private const val HOLD_OPEN = 0.20f
private val EyeKeyframes =
    listOf(
        0.00f to 0.10f,
        0.06f to 1.15f,
        0.10f to 1.00f,
        0.34f to 1.00f,
        0.38f to 0.08f,
        0.42f to 1.00f,
        0.54f to 1.00f,
        0.58f to 0.08f,
        0.62f to 1.00f,
        0.86f to 1.00f,
        1.00f to 0.12f,
    )
private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val BandEasing = CubicBezierEasing(0.3f, 0.6f, 0.3f, 1f)

// The floating z's: position and size in logo units, one 3.2 s loop, the second 1.2 s behind.
// They start and drift a little closer to the head than the prototype so they stay inside the arch.
private const val Z_LOOP_MS = 3_200
private const val Z_SECOND_DELAY = 1_200f / Z_LOOP_MS
private const val Z_STATIC_ALPHA = 0.6f
private val FirstZ = Offset(136f, 62f)
private const val FIRST_Z_SIZE = 11f
private val SecondZ = Offset(143f, 52f)
private const val SECOND_Z_SIZE = 8f

// The two eyes of the logo's awake bear.
private const val EYE_LEFT_X = 94.16f
private const val EYE_RIGHT_X = 116.16f
private const val EYE_Y = 87.7f
private const val EYE_RX = 2.8f
private const val EYE_RY = 3.1f

// The force field, in one 800 ms timeline: the band takes 550 ms and the ring 600 ms of it.
private const val PULSE_MS = 800
private const val PULSE_BAND_MS = 550f
private const val PULSE_RING_MS = 600f
private const val TINT_PEAK = 0.12f
private const val TINT_ALPHA = 0.22f
private const val BAND_TILT = -35f
private const val BAND_WIDTH = 44f
private const val BAND_LENGTH = 360f
private const val BAND_TRAVEL = 300f
private val BandPivot = Offset(105f, 60f)
private const val RING_RADIUS = 22f
private const val RING_STROKE = 2.5f
private const val RING_START_SCALE = 0.15f

/** The eyes' vertical scale at [time] (0 to 1) of the wake; without animations they stay open. */
private fun eyeScaleY(time: Float, animated: Boolean): Float {
  if (!animated) return 1f
  val t = time.coerceIn(0f, 1f)
  for (i in 1 until EyeKeyframes.size) {
    val (t1, v1) = EyeKeyframes[i]
    if (t <= t1) {
      val (t0, v0) = EyeKeyframes[i - 1]
      val local = if (t1 > t0) (t - t0) / (t1 - t0) else 1f
      return lerp(v0, v1, EaseInOut.transform(local))
    }
  }
  return EyeKeyframes.last().second
}

/** The inside of the arch: the logo's arch closed along the horizon. */
private val archInterior =
    Path().apply {
      addPath(archPath)
      close()
    }

/**
 * The logo's bear with its two eyes open as ovals, each squashed to [scaleY] around its own
 * centre. The outline is the logo's and never changes size.
 */
private fun wakingBear(scaleY: Float): Path =
    Path().apply {
      fillType = PathFillType.EvenOdd
      addPath(bearOutlinePath)
      listOf(EYE_LEFT_X, EYE_RIGHT_X).forEach {
        addOval(
            Rect(
                left = it - EYE_RX,
                top = EYE_Y - EYE_RY * scaleY,
                right = it + EYE_RX,
                bottom = EYE_Y + EYE_RY * scaleY,
            )
        )
      }
    }

/**
 * Six small stars as fractions of the header: two in the sky above the arch and two beside each
 * of its sides, never inside the den, so the smaller arch leaves the stars in the visible sky.
 */
private val stars =
    listOf(
        Star(0.38f, 0.10f, 0.8f),
        Star(0.63f, 0.20f, 0.5f),
        Star(0.09f, 0.52f, 0.7f),
        Star(0.14f, 0.79f, 0.5f),
        Star(0.91f, 0.48f, 0.8f),
        Star(0.86f, 0.76f, 0.6f),
    )

/** A star at a fraction of the header's width and of the sky's height, with its own opacity. */
private class Star(val x: Float, val y: Float, val alpha: Float)

private fun DrawScope.drawStars(width: Float, skyTop: Float, sky: Float, ink: Color) {
  stars.forEach { star ->
    drawCircle(
        color = ink.copy(alpha = star.alpha),
        radius = 1.3.dp.toPx(),
        center = Offset(width * star.x, skyTop + sky * star.y),
    )
  }
}

/** The tint's strength over the pulse: 0 to 1 at 12 %, then back to 0, each leg eased out. */
private fun tintStrength(progress: Float): Float =
    if (progress < TINT_PEAK) EaseOut.transform(progress / TINT_PEAK)
    else 1f - EaseOut.transform((progress - TINT_PEAK) / (1f - TINT_PEAK))

/** The arch's own teal to violet, as a gradient across the arch. */
private fun archBrush(start: Color, end: Color, alpha: Float = 1f): Brush =
    Brush.linearGradient(
        colors = listOf(start.copy(alpha = start.alpha * alpha), end.copy(alpha = end.alpha * alpha)),
        start = Offset(ARCH_LEFT, ARCH_BOTTOM),
        end = Offset(ARCH_RIGHT, ARCH_TOP),
    )

private fun DrawScope.drawTint(pulse: DenPulse, start: Color, end: Color) {
  val alpha = TINT_ALPHA * tintStrength(pulse.progress.value)
  if (alpha <= 0f) return
  clipPath(archInterior) {
    drawRect(
        brush = archBrush(start, end, alpha),
        topLeft = Offset(ARCH_LEFT, ARCH_TOP),
        size = Size(ARCH_RIGHT - ARCH_LEFT, ARCH_BOTTOM - ARCH_TOP),
    )
  }
}

/** The teal band: one tilted stripe that sweeps across the den from the bottom left to the top. */
private fun DrawScope.drawBand(pulse: DenPulse, teal: Color) {
  val progress = pulse.progress.value
  val covered = (progress / (PULSE_BAND_MS / PULSE_MS)).coerceIn(0f, 1f)
  val fade = tintStrength(progress)
  if (fade <= 0f) return
  val centerX = BandPivot.x + (BandEasing.transform(covered) - 0.5f) * BAND_TRAVEL
  rotate(degrees = BAND_TILT, pivot = BandPivot) {
    drawRect(
        brush =
            Brush.horizontalGradient(
                colorStops =
                    arrayOf(
                        0f to Color.Transparent,
                        0.5f to teal.copy(alpha = 0.7f * fade),
                        1f to Color.Transparent,
                    ),
                startX = centerX - BAND_WIDTH / 2f,
                endX = centerX + BAND_WIDTH / 2f,
            ),
        topLeft = Offset(centerX - BAND_WIDTH / 2f, BandPivot.y - BAND_LENGTH / 2f),
        size = Size(BAND_WIDTH, BAND_LENGTH),
    )
  }
}

/** The ring in the arch's gradient, growing from the tap point and fading as it grows. */
private fun DrawScope.drawRing(pulse: DenPulse, at: Offset, start: Color, end: Color) {
  val covered = (pulse.progress.value / (PULSE_RING_MS / PULSE_MS)).coerceIn(0f, 1f)
  if (covered <= 0f || covered >= 1f) return
  val eased = EaseOut.transform(covered)
  val scale = lerp(RING_START_SCALE, 1f, eased)
  val radius = RING_RADIUS * scale
  drawCircle(
      brush =
          Brush.linearGradient(
              colors = listOf(start, end),
              start = Offset(at.x - radius, at.y + radius),
              end = Offset(at.x + radius, at.y - radius),
          ),
      radius = radius,
      center = at,
      alpha = 0.9f * (1f - eased),
      style = Stroke(width = RING_STROKE * scale),
  )
}

/** The two z's drifting up and to the right of the sleeping bear's head, or still when static. */
private fun DrawScope.drawSleepZ(clock: Float, animated: Boolean, color: Color) {
  val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
  fun z(origin: Offset, size: Float, phase: Float) {
    val alpha: Float
    var dx = 0f
    var dy = 0f
    if (!animated) {
      alpha = Z_STATIC_ALPHA
    } else {
      val p = ((phase % 1f) + 1f) % 1f
      alpha = if (p < 0.3f) lerp(0f, 0.9f, p / 0.3f) else lerp(0.9f, 0f, (p - 0.3f) / 0.7f)
      val e = EaseOut.transform(p)
      dx = 5f * e
      dy = lerp(4f, -11f, e)
    }
    if (alpha <= 0f) return
    paint.textSize = size
    paint.color = color.copy(alpha = alpha).toArgb()
    drawContext.canvas.nativeCanvas.drawText("z", origin.x + dx, origin.y + dy, paint)
  }
  z(FirstZ, FIRST_Z_SIZE, clock)
  z(SecondZ, SECOND_Z_SIZE, clock - Z_SECOND_DELAY)
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

@Preview(name = "Light", widthDp = 360, heightDp = 560)
@Preview(name = "Dark", widthDp = 360, heightDp = 560, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BearDenPreview() {
  TaigaTheme {
    Column {
      BearDen(modifier = Modifier.fillMaxWidth(), size = DenSize.Header)
      BearDen(modifier = Modifier.fillMaxWidth(), size = DenSize.Compact, glow = true)
    }
  }
}
