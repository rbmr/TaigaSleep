package com.rbmr.taigasleep.ui.components

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import kotlin.math.PI
import kotlin.math.sin
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

  var eyes by remember { mutableStateOf(Eyes.Sleeping) }
  // The two eye holes grow by this factor on a tap; the bear outline never changes size.
  val eyePop = remember { Animatable(1f) }
  var waking by remember { mutableStateOf(false) }
  val pulses = remember { mutableStateListOf<DenPulse>() }

  fun closeAgain() {
    eyes = if (variant == LogoVariant.Awake) Eyes.Open else Eyes.Sleeping
  }

  suspend fun wake() {
    val start = SystemClock.uptimeMillis()
    try {
      eyes = Eyes.Open
      if (animations) {
        eyePop.snapTo(EYE_POP)
        eyePop.animateTo(1f, tween(EYE_POP_MS))
        delayUntil(start + FIRST_BLINK_MS)
        eyes = Eyes.Closed
        delay(BLINK_MS)
        eyes = Eyes.Open
        delayUntil(start + SECOND_BLINK_MS)
        eyes = Eyes.Closed
        delay(BLINK_MS)
        eyes = Eyes.Open
      }
      delayUntil(start + SETTLE_MS)
      closeAgain()
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

    val openEyes =
        when (eyes) {
          Eyes.Open -> true
          Eyes.Closed -> false
          Eyes.Sleeping -> variant == LogoVariant.Awake
        }

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

        val body = if (openEyes) openEyesBear(eyePop.value) else sleepingBearPath
        // The tap animation only moves the eyes: the ink stays the colour of this variant.
        val ink = colors.bear
        drawPath(path = body, color = ink)

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

/** The state of the bear's eyes; [Eyes.Sleeping] also means "not animating". */
private enum class Eyes {
  Sleeping,
  Open,
  Closed,
}

/** One tap of the force field: the tint, the band and the ring share one timeline. */
private class DenPulse(val at: Offset) {
  val progress = Animatable(0f)
}

private suspend fun delayUntil(wallMs: Long) {
  val wait = wallMs - SystemClock.uptimeMillis()
  if (wait > 0) delay(wait)
}

// The wake sequence: the eye pop, the two blinks, then the eyes close again.
private const val EYE_POP = 1.15f
private const val EYE_POP_MS = 150
private const val FIRST_BLINK_MS = 1_000L
private const val SECOND_BLINK_MS = 1_600L
private const val SETTLE_MS = 2_800L
private const val BLINK_MS = 120L

// The two eyes of the logo's awake bear, whose holes grow for a moment when the bear wakes.
private const val EYE_LEFT_X = 94.16f
private const val EYE_RIGHT_X = 116.16f
private const val EYE_Y = 87.7f
private const val EYE_RADIUS = 2.8f

// The force field, in one 800 ms timeline: the band takes 550 ms and the ring 600 ms of it.
private const val PULSE_MS = 800
private const val PULSE_BAND_MS = 550f
private const val PULSE_RING_MS = 600f
private const val BAND_TILT = -35f
private const val BAND_HALF_LENGTH = 34f
private const val BAND_THICKNESS = 6f
private const val RING_MAX_RADIUS = 56f

/** The inside of the arch: the logo's arch closed along the horizon. */
private val archInterior =
    Path().apply {
      addPath(archPath)
      close()
    }

/**
 * The logo's open-eyed bear, with each eye hole cut again at [pop] times its normal radius around
 * the eye's own centre. The outline is the logo's and never changes size.
 */
private fun openEyesBear(pop: Float): Path {
  if (pop <= 1f) return awakeBearPath
  val first = Path()
  first.op(awakeBearPath, eyeHole(EYE_LEFT_X, pop), PathOperation.Difference)
  val both = Path()
  both.op(first, eyeHole(EYE_RIGHT_X, pop), PathOperation.Difference)
  both.fillType = PathFillType.EvenOdd
  return both
}

private fun eyeHole(centerX: Float, pop: Float): Path =
    Path().apply {
      addOval(Rect(center = Offset(centerX, EYE_Y), radius = EYE_RADIUS * pop))
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

private fun DrawScope.drawTint(pulse: DenPulse, start: Color, end: Color) {
  val progress = pulse.progress.value
  val alpha = 0.2f * sin(PI * progress).toFloat()
  if (alpha <= 0f) return
  clipPath(archInterior) {
    drawRect(
        brush =
            Brush.verticalGradient(
                colors = listOf(start.copy(alpha = alpha), end.copy(alpha = alpha * 0.6f)),
                startY = ARCH_TOP,
                endY = ARCH_BOTTOM,
            ),
        topLeft = Offset(ARCH_LEFT, ARCH_TOP),
        size = Size(ARCH_RIGHT - ARCH_LEFT, ARCH_BOTTOM - ARCH_TOP),
    )
  }
}

/** The teal band: a tilted stripe that crosses the den entrance from the bottom left to the top. */
private fun DrawScope.drawBand(pulse: DenPulse, teal: Color) {
  val covered = (pulse.progress.value / (PULSE_BAND_MS / PULSE_MS)).coerceIn(0f, 1f)
  if (covered <= 0f || covered >= 1f) return
  val fade = sin(PI * covered).toFloat()
  val center =
      Offset(
          lerp(ARCH_LEFT + 24f, ARCH_RIGHT - 54f, covered),
          lerp(ARCH_BOTTOM - 10f, ARCH_TOP + 24f, covered),
      )
  rotate(degrees = BAND_TILT, pivot = center) {
    drawRect(
        brush =
            Brush.horizontalGradient(
                colorStops =
                    arrayOf(
                        0f to Color.Transparent,
                        0.5f to teal.copy(alpha = 0.6f * fade),
                        1f to Color.Transparent,
                    ),
                startX = center.x - BAND_HALF_LENGTH,
                endX = center.x + BAND_HALF_LENGTH,
            ),
        topLeft = Offset(center.x - BAND_HALF_LENGTH, center.y - BAND_THICKNESS / 2f),
        size = Size(BAND_HALF_LENGTH * 2f, BAND_THICKNESS),
    )
  }
}

/** The ring in the arch's gradient, growing from the tap point and fading as it grows. */
private fun DrawScope.drawRing(pulse: DenPulse, at: Offset, start: Color, end: Color) {
  val covered = (pulse.progress.value / (PULSE_RING_MS / PULSE_MS)).coerceIn(0f, 1f)
  if (covered <= 0f || covered >= 1f) return
  val radius = RING_MAX_RADIUS * covered
  drawCircle(
      brush =
          Brush.linearGradient(
              colors = listOf(start, end),
              start = Offset(at.x - radius, at.y - radius),
              end = Offset(at.x + radius, at.y + radius),
          ),
      radius = radius,
      center = at,
      alpha = 1f - covered,
      style = Stroke(width = 3f),
  )
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
