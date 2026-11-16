package com.rbmr.taigasleep.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
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
  NOTIFICATIONS,
  DISPLAY,
  NIGHT,
  GUIDANCE,
  LICENCES,
  ABOUT,
  NONE,
}

/** The one thing open above the pager, from every page the user opened. */
internal fun mainOverlay(open: Collection<MainOverlay>): MainOverlay =
    MainOverlay.entries.firstOrNull { it != MainOverlay.NONE && it in open } ?: MainOverlay.NONE

/**
 * What the user opened above the tabs: pages stacked on the tabs, and the recording a History row
 * opened. Only the one that is on top is drawn, and Back closes it to the page beneath, so the
 * licences open from About return to About. It is the screen's own state, saved with it.
 */
internal class MainNavigation(
    open: List<MainOverlay> = emptyList(),
    detailLocal: String? = null,
    detailRemote: String? = null,
    detailAnalysis: String? = null,
) {
  var open: List<MainOverlay> by mutableStateOf(open)
    private set

  var detailLocal: String? by mutableStateOf(detailLocal)
    private set

  var detailRemote: String? by mutableStateOf(detailRemote)
    private set

  var detailAnalysis: String? by mutableStateOf(detailAnalysis)
    private set

  /** The page on top of the tabs, or [MainOverlay.NONE] when the tabs are. */
  val top: MainOverlay
    get() = mainOverlay(open)

  /** Opens [overlay] above what is already open. */
  fun show(overlay: MainOverlay) {
    if (overlay != MainOverlay.NONE && overlay !in open) open = open + overlay
  }

  /** Closes the page on top, showing the one it was opened from. */
  fun hide() {
    val closed = top
    if (closed == MainOverlay.NONE) return
    open = open - closed
    if (closed == MainOverlay.NIGHT) {
      detailLocal = null
      detailRemote = null
      detailAnalysis = null
    }
  }

  /** Opens the recording a row names: the local id, the server id and the analysis, if any. */
  fun openRecording(local: String?, remote: String?, analysis: String?) {
    detailLocal = local
    detailRemote = remote
    detailAnalysis = analysis
    show(MainOverlay.NIGHT)
  }

  companion object {
    /** Its state survives a rotation as the state of the screen it belongs to. */
    val Saver: Saver<MainNavigation, Any> =
        listSaver(
            save = { listOf(it.open, it.detailLocal, it.detailRemote, it.detailAnalysis) },
            restore = {
              MainNavigation(
                  open = it[0] as List<MainOverlay>,
                  detailLocal = it[1] as String?,
                  detailRemote = it[2] as String?,
                  detailAnalysis = it[3] as String?,
              )
            },
        )
  }
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
