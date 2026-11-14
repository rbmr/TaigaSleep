package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The smart alarm window is stored as a start and a length; older saved windows are converted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingPrefsWakeTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = RecordingPrefs.get(context)
  // The prefs object lives as long as the process, so the raw view has to be the one it holds.
  private val raw: SharedPreferences =
      RecordingPrefs::class.java.getDeclaredField("prefs").let {
        it.isAccessible = true
        it.get(prefs) as SharedPreferences
      }

  @Test
  fun `a window with a start and a length is read back`() {
    prefs.setWakeWindow("a", WakeWindow(6 * 60 + 30, 45, heartRate = false, movement = true))

    assertEquals(WakeWindow(6 * 60 + 30, 45, heartRate = false, movement = true), prefs.wakeWindow("a"))
  }

  @Test
  fun `a window saved with from and to times becomes a start and a length`() {
    raw.edit().putInt("wake_start_b", 6 * 60 + 30).putInt("wake_end_b", 7 * 60).commit()

    assertEquals(WakeWindow(6 * 60 + 30, 30), prefs.wakeWindow("b"))
  }

  @Test
  fun `a window saved across midnight keeps its length`() {
    raw.edit().putInt("wake_start_c", 23 * 60 + 30).putInt("wake_end_c", 0 * 60 + 30).commit()

    assertEquals(60, prefs.wakeWindow("c")?.lengthMinutes)
  }

  @Test
  fun `nothing saved means no window`() {
    assertNull(prefs.wakeWindow("none"))
  }
}
