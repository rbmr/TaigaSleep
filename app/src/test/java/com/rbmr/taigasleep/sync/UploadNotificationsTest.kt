package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNotificationsTest {
  @Test
  fun `the notification opens the launcher activity the manifest declares`() {
    assertEquals(MainActivity::class.java.name, UploadNotifications.APP_ACTIVITY)
  }
}
