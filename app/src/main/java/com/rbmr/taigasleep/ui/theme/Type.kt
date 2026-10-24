package com.rbmr.taigasleep.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rbmr.taigasleep.R

// One variable font file; each weight the design uses is a variation of it.
@OptIn(ExperimentalTextApi::class)
private fun albertSans(weight: FontWeight) =
    Font(
        resId = R.font.albert_sans,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

val AlbertSans =
    FontFamily(
        albertSans(FontWeight.Normal),
        albertSans(FontWeight.Medium),
        albertSans(FontWeight.SemiBold),
        albertSans(FontWeight.Bold),
    )

// Digits line up in columns, so timers and readings do not jitter.
private const val TABULAR = "tnum"

private fun style(
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
) =
    TextStyle(
        fontFamily = AlbertSans,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
        fontFeatureSettings = TABULAR,
    )

val TaigaTypography =
    Typography(
        displayLarge = style(FontWeight.Bold, 34, 38, -0.68),
        displayMedium = style(FontWeight.SemiBold, 48, 50, -0.96),
        displaySmall = style(FontWeight.Bold, 30, 34, -0.6),
        headlineLarge = style(FontWeight.Bold, 32, 38, -0.64),
        headlineMedium = style(FontWeight.Bold, 30, 34, -0.6),
        headlineSmall = style(FontWeight.SemiBold, 22, 28),
        titleLarge = style(FontWeight.SemiBold, 17, 24),
        titleMedium = style(FontWeight.SemiBold, 16, 22),
        titleSmall = style(FontWeight.SemiBold, 15, 20),
        bodyLarge = style(FontWeight.Normal, 16, 24),
        bodyMedium = style(FontWeight.Normal, 14, 20),
        bodySmall = style(FontWeight.Normal, 13, 18),
        labelLarge = style(FontWeight.SemiBold, 15, 20),
        labelMedium = style(FontWeight.SemiBold, 12, 16),
        labelSmall = style(FontWeight.SemiBold, 12, 16),
    )
