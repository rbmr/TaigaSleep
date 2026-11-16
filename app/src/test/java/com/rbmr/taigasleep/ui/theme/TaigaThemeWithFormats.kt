package com.rbmr.taigasleep.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.rbmr.taigasleep.insights.Formats

/** [TaigaTheme] with the formatter the activity would provide, for tests that pick their own. */
@Composable
fun TaigaThemeWithFormats(formats: Formats, content: @Composable () -> Unit) {
  TaigaTheme { CompositionLocalProvider(LocalFormats provides formats) { content() } }
}
