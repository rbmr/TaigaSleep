package com.rbmr.taigasleep.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which palette the app draws with: the unchanged one where the phone asks for nothing more, a
 * stronger one for each of the platform's higher contrast levels, and the unchanged one again on a
 * phone that predates the setting.
 */
class ContrastLevelTest {
  @Test
  fun `the standard level resolves to the unchanged palette`() {
    assertSame(LightPalette, paletteFor(darkTheme = false, contrast = ContrastLevel.STANDARD))
    assertSame(DarkPalette, paletteFor(darkTheme = true, contrast = ContrastLevel.STANDARD))
    assertSame(LightColors, paletteFor(darkTheme = false, contrast = ContrastLevel.STANDARD).scheme)
    assertSame(LightTaigaColors, paletteFor(darkTheme = false, contrast = ContrastLevel.STANDARD).colors)
    assertSame(DarkColors, paletteFor(darkTheme = true, contrast = ContrastLevel.STANDARD).scheme)
    assertSame(DarkTaigaColors, paletteFor(darkTheme = true, contrast = ContrastLevel.STANDARD).colors)
  }

  @Test
  fun `the medium level resolves to the medium palette in both themes`() {
    assertSame(LightMediumPalette, paletteFor(darkTheme = false, contrast = ContrastLevel.MEDIUM))
    assertSame(DarkMediumPalette, paletteFor(darkTheme = true, contrast = ContrastLevel.MEDIUM))
    assertNotEquals(LightTaigaColors.stageLight, LightMediumPalette.colors.stageLight)
    assertNotEquals(DarkTaigaColors.stageLight, DarkMediumPalette.colors.stageLight)
  }

  @Test
  fun `the high level resolves to the high palette in both themes`() {
    assertSame(LightHighPalette, paletteFor(darkTheme = false, contrast = ContrastLevel.HIGH))
    assertSame(DarkHighPalette, paletteFor(darkTheme = true, contrast = ContrastLevel.HIGH))
    assertNotEquals(LightTaigaColors.stageLight, LightHighPalette.colors.stageLight)
    assertNotEquals(DarkTaigaColors.stageLight, DarkHighPalette.colors.stageLight)
    assertNotEquals(LightMediumPalette.colors.stageLight, LightHighPalette.colors.stageLight)
    assertNotEquals(DarkMediumPalette.colors.stageLight, DarkHighPalette.colors.stageLight)
  }

  @Test
  fun `the platform's contrast value resolves to its level`() {
    // The three levels the phone offers are 0, 0.5 and 1; less than the standard stays standard.
    assertEquals(ContrastLevel.STANDARD, ContrastLevel.from(-1f))
    assertEquals(ContrastLevel.STANDARD, ContrastLevel.from(0f))
    assertEquals(ContrastLevel.MEDIUM, ContrastLevel.from(0.5f))
    assertEquals(ContrastLevel.HIGH, ContrastLevel.from(1f))
  }

  @Test
  fun `a phone without the contrast setting stays at the standard level`() {
    assertEquals(ContrastLevel.STANDARD, systemContrastLevel(sdkInt = 31, contrast = 0f))
    assertEquals(ContrastLevel.STANDARD, systemContrastLevel(sdkInt = 33, contrast = 0.5f))
    assertEquals(ContrastLevel.STANDARD, systemContrastLevel(sdkInt = 33, contrast = 1f))
    assertEquals(ContrastLevel.MEDIUM, systemContrastLevel(sdkInt = 34, contrast = 0.5f))
    assertEquals(ContrastLevel.HIGH, systemContrastLevel(sdkInt = 34, contrast = 1f))
  }
}
