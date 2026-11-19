package com.rbmr.taigasleep.ui.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rbmr.taigasleep.recording.SoundChoice
import com.rbmr.taigasleep.tracker.RecordingMode
import com.rbmr.taigasleep.tracker.StreamType
import com.rbmr.taigasleep.ui.theme.TaigaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Sound section of the configuration sheet: what it offers in each state and what it sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SoundSectionTest {
  @get:Rule val compose = createComposeRule()

  private var chosen: SoundChoice? = null

  private fun show(
      sound: SoundChoice,
      mode: RecordingMode = RecordingMode.STREAM,
      denied: Boolean = false,
  ) {
    compose.setContent {
      TaigaTheme {
        SoundSection(
            config =
                StartConfig(
                    streams = Streams(setOf("hr")),
                    modes = setOf(RecordingMode.STREAM, RecordingMode.ON_DEVICE),
                    mode = mode,
                    canRecordAcc = false,
                    options = listOf(StreamOption("hr", StreamType.HEART_RATE)),
                    sound = sound,
                ),
            microphone = MicrophonePermission(denied = denied),
            onSound = { chosen = it },
        )
      }
    }
    compose.waitForIdle()
  }

  @Test
  fun `sound off leaves the codecs in place and unselectable`() {
    show(SoundChoice.OFF)

    compose.onNodeWithText("Compressed").assertIsNotEnabled()
    compose.onNodeWithText("Uncompressed").assertIsNotEnabled()
  }

  @Test
  fun `turning sound on chooses compressed`() {
    show(SoundChoice.OFF)

    compose.onNodeWithText("Enabled").performClick()

    assertEquals(SoundChoice.COMPRESSED, chosen)
  }

  @Test
  fun `compressed sound can be changed to uncompressed`() {
    show(SoundChoice.COMPRESSED)

    compose.onNodeWithText("Uncompressed").assertIsEnabled()
    compose.onNodeWithText("Uncompressed").performClick()

    assertEquals(SoundChoice.UNCOMPRESSED, chosen)
  }

  @Test
  fun `turning sound off sends off`() {
    show(SoundChoice.COMPRESSED)

    compose.onNodeWithText("Enabled").performClick()

    assertEquals(SoundChoice.OFF, chosen)
  }

  @Test
  fun `uncompressed sound can be changed to compressed`() {
    show(SoundChoice.UNCOMPRESSED)

    compose.onNodeWithText("Compressed").assertIsEnabled()
    compose.onNodeWithText("Compressed").performClick()

    assertEquals(SoundChoice.COMPRESSED, chosen)
  }

  @Test
  fun `an on-device night keeps the section in place and says why it is off`() {
    show(SoundChoice.OFF, mode = RecordingMode.ON_DEVICE)

    compose.onNodeWithText("Sound").assertExists()
    compose.onNodeWithText("Enabled").assertIsNotEnabled()
    compose.onNodeWithText("Compressed").assertIsNotEnabled()
    compose.onNodeWithText("Sound needs the phone to receive data all night.").assertExists()
    compose.onNodeWithText("Uncompressed").performClick()
    assertNull("a disabled section sends nothing", chosen)
  }

  @Test
  fun `a refused microphone explains what it is for`() {
    show(SoundChoice.OFF, denied = true)

    compose.onNodeWithText("Allow the microphone to record sound.").assertExists()
  }
}
