package com.rbmr.taigasleep.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.rbmr.taigasleep.R

/**
 * The main pager's tabs, in the order the bottom bar shows them. A tab's ordinal is the page it
 * sits on, so the bar and the pager read the same list in the same order.
 */
internal enum class MainTab(
    val labelRes: Int,
    val icon: ImageVector,
) {
  HISTORY(R.string.nav_history, Icons.Outlined.History),
  HOME(R.string.nav_home, Icons.Outlined.Home),
  SETTINGS(R.string.nav_settings, Icons.Outlined.Tune),
}

/**
 * What is drawn above the main pager. At most one of these is open at a time, and the order here is
 * the order the app checks them in, so the one that is open is the one the user sees and the one
 * Back closes first. [NONE] means the pager itself is on top.
 */
internal enum class MainOverlay {
  REMOTE,
  TOKENS,
  PERSONAL,
  UPLOADS,
  ANALYSIS,
  NIGHT,
  GUIDANCE,
  ABOUT,
  NONE,
}

/** The one thing open above the pager, from what is open. */
internal fun mainOverlay(
    remote: Boolean = false,
    tokens: Boolean = false,
    personal: Boolean = false,
    uploads: Boolean = false,
    analysis: Boolean = false,
    night: Boolean = false,
    guidance: Boolean = false,
    about: Boolean = false,
): MainOverlay =
    when {
      remote -> MainOverlay.REMOTE
      tokens -> MainOverlay.TOKENS
      personal -> MainOverlay.PERSONAL
      uploads -> MainOverlay.UPLOADS
      analysis -> MainOverlay.ANALYSIS
      night -> MainOverlay.NIGHT
      guidance -> MainOverlay.GUIDANCE
      about -> MainOverlay.ABOUT
      else -> MainOverlay.NONE
    }

/**
 * Whether Back on [tab] returns to Home instead of leaving the app. It does on every tab but Home:
 * on Home there is nothing left to close, so Back is not handled here and reaches the system, which
 * leaves the app as it always has.
 *
 * A thing shown above the pager answers Back itself first, so while one is open Back is not the
 * tab's. A night is one of them, which is why closing a night keeps the tab it was opened from.
 */
internal fun backReturnsHome(
    overlay: MainOverlay,
    tab: MainTab,
): Boolean = overlay == MainOverlay.NONE && tab != MainTab.HOME
