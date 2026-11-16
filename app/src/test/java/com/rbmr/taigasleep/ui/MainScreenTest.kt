package com.rbmr.taigasleep.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back on the main screen. The tabs answer it themselves from every page but Home, and whatever is
 * drawn above them closes first, so the tab the user was on is the tab they return to.
 */
class MainScreenTest {
  @Test
  fun `back on History selects Home and the app stays open`() {
    assertTrue(backReturnsHome(MainOverlay.NONE, MainTab.HISTORY))
  }

  @Test
  fun `back on Settings selects Home and the app stays open`() {
    assertTrue(backReturnsHome(MainOverlay.NONE, MainTab.SETTINGS))
  }

  @Test
  fun `back on Home is left to the system and finishes the activity as before`() {
    assertFalse(backReturnsHome(MainOverlay.NONE, MainTab.HOME))
  }

  @Test
  fun `back with a night open closes the night and the tab stays where it was`() {
    // The night is drawn instead of the pager and closes on its own handler, so Back never returns
    // to Home here and the page the night was opened from is still there when it closes.
    assertEquals(MainOverlay.NIGHT, mainOverlay(listOf(MainOverlay.NIGHT)))
    assertFalse(backReturnsHome(MainOverlay.NIGHT, MainTab.HISTORY))
  }

  @Test
  fun `a settings page or a sheet above the pager closes before the tab does`() {
    for (overlay in MainOverlay.entries - MainOverlay.NONE) {
      assertFalse(
          "$overlay: Back belongs to what is open",
          backReturnsHome(overlay, MainTab.HISTORY),
      )
    }
  }

  @Test
  fun `the pager is what is on top when nothing is open`() {
    assertEquals(MainOverlay.NONE, mainOverlay(emptyList()))
  }

  @Test
  fun `the first thing open is the one on top`() {
    assertEquals(MainOverlay.NIGHT, mainOverlay(listOf(MainOverlay.NIGHT, MainOverlay.GUIDANCE)))
    assertEquals(
        MainOverlay.REMOTE,
        mainOverlay(listOf(MainOverlay.REMOTE, MainOverlay.NIGHT, MainOverlay.ABOUT)),
    )
    assertEquals(MainOverlay.GUIDANCE, mainOverlay(listOf(MainOverlay.GUIDANCE, MainOverlay.ABOUT)))
  }

  @Test
  fun `the licences are on top of About, which they were opened from`() {
    assertEquals(
        MainOverlay.LICENCES,
        mainOverlay(listOf(MainOverlay.ABOUT, MainOverlay.LICENCES)),
    )
    assertEquals(MainOverlay.ABOUT, mainOverlay(listOf(MainOverlay.ABOUT)))
  }

  @Test
  fun `a page closes back to the one it was opened from`() {
    val nav = MainNavigation()

    nav.show(MainOverlay.ABOUT)
    nav.show(MainOverlay.LICENCES)
    assertEquals(MainOverlay.LICENCES, nav.top)

    nav.hide()
    assertEquals(MainOverlay.ABOUT, nav.top)

    nav.hide()
    assertEquals(MainOverlay.NONE, nav.top)
  }

  @Test
  fun `a recording opened from a row carries its ids and forgets them when it closes`() {
    val nav = MainNavigation()

    nav.openRecording(local = "local", remote = "remote", analysis = "analysis")
    assertEquals(MainOverlay.NIGHT, nav.top)
    assertEquals("local", nav.detailLocal)
    assertEquals("remote", nav.detailRemote)
    assertEquals("analysis", nav.detailAnalysis)

    nav.hide()
    assertEquals(MainOverlay.NONE, nav.top)
    assertNull(nav.detailLocal)
    assertNull(nav.detailRemote)
    assertNull(nav.detailAnalysis)
  }

  @Test
  fun `the same page opened twice is open once`() {
    val nav = MainNavigation()

    nav.show(MainOverlay.DISPLAY)
    nav.show(MainOverlay.DISPLAY)
    nav.hide()

    assertEquals(MainOverlay.NONE, nav.top)
  }
}
