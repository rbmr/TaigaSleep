package com.rbmr.taigasleep

import android.app.Application
import android.content.Context
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.sync.UploadSettings
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The stores a screen reads through the container: one instance each, built once, so a screen does
 * not construct one on the main thread while the app starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val application: Application = RuntimeEnvironment.getApplication()

  @Test
  fun `the guidance store is one container instance over the app preferences`() {
    val container = AppContainer(application)
    val store = container.guidanceStore

    assertSame(store, container.guidanceStore)
    // The step the user confirmed is in the app's own preferences, not in a copy of them.
    store.setDone("flow", "step", true)
    assertTrue(GuidanceStore(context).isDone("flow", "step"))
  }

  @Test
  fun `the recorder preferences and the upload settings are the app singletons`() {
    val container = AppContainer(application)

    assertSame(RecordingPrefs.get(context), container.recordingPrefs)
    assertSame(UploadSettings.get(context), container.uploadSettings)
    assertSame(container.recordingPrefs, container.recordingPrefs)
    assertSame(container.uploadSettings, container.uploadSettings)
  }
}
