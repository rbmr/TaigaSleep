package com.rbmr.taigasleep.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colors the Material scheme has no slot for, plus the exact values the design uses. Screens read
 * them through `MaterialTheme.taiga`; nothing outside this file should spell a hex value.
 */
@Immutable
class TaigaColors(
    val dark: Boolean,
    val ok: Color,
    val okContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val warnBorder: Color,
    val onWarn: Color,
    // Text and track colors on the warn container (pending-upload banner).
    val warnInk: Color,
    val warnTrack: Color,
    val warnBar: Color,
    val bad: Color,
    val badContainer: Color,
    val badBorder: Color,
    // Top color of the screen background gradient.
    val hero: Color,
    val disabled: Color,
    // Unfilled progress segments.
    val segment: Color,
    // Borders of secondary buttons.
    val strong: Color,
    val auroraStart: Color,
    val auroraEnd: Color,
    val bear: Color,
    val stageWake: Color,
    val stageRem: Color,
    val stageLight: Color,
    val stageDeep: Color,
    // Heart-rate curve; deliberately outside the sleep-stage palette.
    val chartHr: Color,
    // The non-colour tokens travel with the colours so `MaterialTheme.taiga` reaches all of them.
    val text: TaigaText = TaigaText,
    val space: TaigaSpacing = TaigaSpacing,
    val shape: TaigaShapes = TaigaShapes,
)

val LightTaigaColors =
    TaigaColors(
        dark = false,
        ok = Color(0xFF17614D),
        okContainer = Color(0xFFD5EBE2),
        warn = Color(0xFF8A5A12),
        warnContainer = Color(0xFFFBEBD0),
        warnBorder = Color(0xFFE8C98E),
        onWarn = Color(0xFFFFFFFF),
        warnInk = Color(0xFF5C3C08),
        warnTrack = Color(0xFFEBD3A6),
        warnBar = Color(0xFFB7791F),
        bad = Color(0xFFA3321F),
        badContainer = Color(0xFFF9DDD6),
        badBorder = Color(0xFFF0B9AD),
        hero = Color(0xFFD5EBE2),
        disabled = Color(0xFF7A8F85),
        segment = Color(0xFFC5D5CC),
        strong = Color(0xFFB7C9BF),
        auroraStart = Color(0xFF2FB89A),
        auroraEnd = Color(0xFF8E7CC3),
        bear = Color(0xFF111111),
        stageWake = Color(0xFFD99A4E),
        stageRem = Color(0xFF8E6AD8),
        stageLight = Color(0xFF5BA8E0),
        stageDeep = Color(0xFF4B59B8),
        chartHr = Color(0xFFB23A48),
    )

val DarkTaigaColors =
    TaigaColors(
        dark = true,
        ok = Color(0xFF3DD6B0),
        okContainer = Color(0xFF16342C),
        warn = Color(0xFFF2B35E),
        warnContainer = Color(0xFF33260F),
        warnBorder = Color(0xFF5A421A),
        onWarn = Color(0xFF2A1D07),
        warnInk = Color(0xFFF2D9A6),
        warnTrack = Color(0xFF5A421A),
        warnBar = Color(0xFFF2B35E),
        bad = Color(0xFFFF8F7A),
        badContainer = Color(0xFF3C1D17),
        badBorder = Color(0xFF5A2E25),
        hero = Color(0xFF16463A),
        disabled = Color(0xFF6F8A7E),
        segment = Color(0xFF233730),
        strong = Color(0xFF3A5249),
        auroraStart = Color(0xFF3DD6B0),
        auroraEnd = Color(0xFFA57BE8),
        bear = Color(0xFFFFFFFF),
        stageWake = Color(0xFFF2B35E),
        stageRem = Color(0xFFA57BE8),
        stageLight = Color(0xFF6FB7F2),
        stageDeep = Color(0xFF7B8CFF),
        chartHr = Color(0xFFFF9AA8),
    )

/** How far the medium contrast palette moves its text and graphics towards the theme's ink. */
internal const val CONTRAST_MEDIUM_STRENGTH = 0.45f

/** How far the high contrast palette moves its text and graphics towards the theme's ink. */
internal const val CONTRAST_HIGH_STRENGTH = 0.7f

val LightMediumTaigaColors = LightTaigaColors.atHigherContrast(CONTRAST_MEDIUM_STRENGTH)
val LightHighTaigaColors = LightTaigaColors.atHigherContrast(CONTRAST_HIGH_STRENGTH)
val DarkMediumTaigaColors = DarkTaigaColors.atHigherContrast(CONTRAST_MEDIUM_STRENGTH)
val DarkHighTaigaColors = DarkTaigaColors.atHigherContrast(CONTRAST_HIGH_STRENGTH)

/**
 * The same palette with stronger ink: every colour that is read as text or drawn as a graphic moves
 * [amount] of the way to [ink], so it stands further from the backgrounds it sits on. Backgrounds
 * and container fills keep their value, and so does the text that sits on a fill, which is already
 * as strong as it goes against it.
 */
internal fun TaigaColors.atHigherContrast(amount: Float, ink: Color = bear): TaigaColors =
    TaigaColors(
        dark = dark,
        ok = ok.towards(ink, amount),
        okContainer = okContainer,
        warn = warn.towards(ink, amount),
        warnContainer = warnContainer,
        warnBorder = warnBorder.towards(ink, amount),
        onWarn = onWarn,
        warnInk = warnInk.towards(ink, amount),
        warnTrack = warnTrack,
        warnBar = warnBar.towards(ink, amount),
        bad = bad.towards(ink, amount),
        badContainer = badContainer,
        badBorder = badBorder.towards(ink, amount),
        hero = hero,
        disabled = disabled,
        segment = segment,
        strong = strong.towards(ink, amount),
        auroraStart = auroraStart.towards(ink, amount),
        auroraEnd = auroraEnd.towards(ink, amount),
        bear = bear,
        stageWake = stageWake.towards(ink, amount),
        stageRem = stageRem.towards(ink, amount),
        stageLight = stageLight.towards(ink, amount),
        stageDeep = stageDeep.towards(ink, amount),
        chartHr = chartHr.towards(ink, amount),
    )

/**
 * This colour moved [amount] of the way to [ink] in sRGB: the hue stays what it was while the colour
 * stands further from a background of the opposite brightness.
 */
internal fun Color.towards(ink: Color, amount: Float): Color =
    Color(
        red = red + (ink.red - red) * amount,
        green = green + (ink.green - green) * amount,
        blue = blue + (ink.blue - blue) * amount,
        alpha = alpha,
    )

// Night sky of the launcher icon, also used as the dark app background.
val TaigaNight = Color(0xFF0A1210)
