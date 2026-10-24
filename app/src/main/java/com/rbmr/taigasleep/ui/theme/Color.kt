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
    val bearAwake: Color,
    val stageWake: Color,
    val stageRem: Color,
    val stageLight: Color,
    val stageDeep: Color,
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
        bear = Color(0xFF0E3B31),
        bearAwake = Color(0xFF3A2A14),
        stageWake = Color(0xFFD99A4E),
        stageRem = Color(0xFF8E6AD8),
        stageLight = Color(0xFF5BA8E0),
        stageDeep = Color(0xFF4B59B8),
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
        bear = Color(0xFFE6F0EA),
        bearAwake = Color(0xFFF5E9D6),
        stageWake = Color(0xFFF2B35E),
        stageRem = Color(0xFFA57BE8),
        stageLight = Color(0xFF6FB7F2),
        stageDeep = Color(0xFF7B8CFF),
    )

// Night sky of the launcher icon, also used as the dark app background.
val TaigaNight = Color(0xFF0A1210)
