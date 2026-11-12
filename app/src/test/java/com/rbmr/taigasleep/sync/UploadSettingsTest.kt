package com.rbmr.taigasleep.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadSettingsTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Before
  fun clear() {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun `a phone that never chose removes the raw data after the upload`() {
    assertEquals(RawRetentionMode.IMMEDIATE, UploadSettings(context).rawRetention.value)
  }

  @Test
  fun `a stored retention choice is kept`() {
    UploadSettings(context).setRawRetention(RawRetentionMode.DAYS_14)

    assertEquals(RawRetentionMode.DAYS_14, UploadSettings(context).rawRetention.value)
  }

  @Test
  fun `keeping all the raw data stays a choice`() {
    UploadSettings(context).setRawRetention(RawRetentionMode.NEVER)

    assertEquals(RawRetentionMode.NEVER, UploadSettings(context).rawRetention.value)
  }

  @Test
  fun `uploads still start by themselves and compressed`() {
    val settings = UploadSettings(context)

    assertTrue(settings.autoUpload.value.enabled)
    assertTrue(settings.compressUploads.value)
  }
}
