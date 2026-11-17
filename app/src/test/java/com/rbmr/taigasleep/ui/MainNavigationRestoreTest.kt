package com.rbmr.taigasleep.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the user opened above the tabs and the recording it names are the screen's own state: a
 * rotation keeps them, as it keeps the page of the pager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainNavigationRestoreTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `an open page and the recording it names survive a recreation`() {
    val restoration = StateRestorationTester(compose)
    var nav: MainNavigation? = null
    restoration.setContent {
      val state = rememberSaveable(saver = MainNavigation.Saver) { MainNavigation() }
      nav = state
      Text(state.top.name)
    }

    compose.runOnIdle { nav?.openRecording(local = "local", remote = "remote", analysis = null) }
    compose.onNodeWithText(MainOverlay.NIGHT.name).assertExists()

    restoration.emulateSavedInstanceStateRestore()

    compose.onNodeWithText(MainOverlay.NIGHT.name).assertExists()
    compose.runOnIdle {
      assert(nav?.detailLocal == "local") { "the local id was lost" }
      assert(nav?.detailRemote == "remote") { "the remote id was lost" }
    }
  }
}
