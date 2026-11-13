package com.rbmr.taigasleep.ui.home

import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Test

class StopBodyTest {
  @Test
  fun `the stop text says whether the recording is uploaded by itself`() {
    assertEquals(R.string.rec_stop_body_auto_upload, stopBody(onDevice = false, autoUpload = true))
    assertEquals(R.string.rec_stop_body, stopBody(onDevice = false, autoUpload = false))
  }

  @Test
  fun `a tracker that stores the recording fetches it first`() {
    assertEquals(
        R.string.rec_stop_body_on_device_auto_upload,
        stopBody(onDevice = true, autoUpload = true),
    )
    assertEquals(R.string.rec_stop_body_on_device, stopBody(onDevice = true, autoUpload = false))
  }
}
