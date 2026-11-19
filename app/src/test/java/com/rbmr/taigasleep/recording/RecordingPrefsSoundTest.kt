package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The microphone choice is stored with the tracker's other stream choices; it is off by default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingPrefsSoundTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = RecordingPrefs.get(context)
  // The prefs object lives as long as the process, so the raw view has to be the one it holds.
  private val raw: SharedPreferences =
      RecordingPrefs::class.java.getDeclaredField("prefs").let {
        it.isAccessible = true
        it.get(prefs) as SharedPreferences
      }

  @Test
  fun `nothing saved means sound is off`() {
    assertEquals(SoundChoice.OFF, prefs.sound("no-sound"))
  }

  @Test
  fun `each tracker keeps its own sound choice`() {
    prefs.setSound("tracker-a", SoundChoice.COMPRESSED)
    prefs.setSound("tracker-b", SoundChoice.UNCOMPRESSED)

    assertEquals(SoundChoice.COMPRESSED, prefs.sound("tracker-a"))
    assertEquals(SoundChoice.UNCOMPRESSED, prefs.sound("tracker-b"))
    assertEquals(SoundChoice.OFF, prefs.sound("tracker-c"))
  }

  @Test
  fun `a choice saved off is read back as off`() {
    prefs.setSound("tracker-d", SoundChoice.COMPRESSED)
    prefs.setSound("tracker-d", SoundChoice.OFF)

    assertEquals(SoundChoice.OFF, prefs.sound("tracker-d"))
  }

  @Test
  fun `a choice this version does not know reads as off`() {
    raw.edit().putString("sound_future", "lossless").commit()

    assertEquals(SoundChoice.OFF, prefs.sound("future"))
  }
}
