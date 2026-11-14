package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The global choice of what the alarm does with the recording is stored and read back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeAlarmSettingsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val settings = WakeAlarmSettings.get(context)
  // The store lives as long as the process, so the raw view has to be the one it holds.
  private val raw: SharedPreferences =
      WakeAlarmSettings::class.java.getDeclaredField("prefs").let {
        it.isAccessible = true
        it.get(settings) as SharedPreferences
      }

  @Before
  fun clear() {
    raw.edit().clear().commit()
  }

  @Test
  fun `optional is the default`() {
    assertEquals(EndRecordingOnAlarm.OPTIONAL, settings.endRecordingOnAlarm)
  }

  @Test
  fun `each choice is read back`() {
    for (choice in EndRecordingOnAlarm.entries) {
      settings.endRecordingOnAlarm = choice
      assertEquals(choice, settings.endRecordingOnAlarm)
    }
  }
}
