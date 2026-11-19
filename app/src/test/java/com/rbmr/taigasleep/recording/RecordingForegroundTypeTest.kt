package com.rbmr.taigasleep.recording

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The foreground type a recording asks for: the tracker link always, the microphone with sound. */
class RecordingForegroundTypeTest {
  @Test
  fun `a recording without sound is a connected device only`() {
    val type = recordingForegroundType(holdsSound = false)

    assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, type)
    assertEquals("connectedDevice", foregroundTypeName(type))
  }

  @Test
  fun `a recording with sound also declares the microphone`() {
    val type = recordingForegroundType(holdsSound = true)

    assertEquals(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        type,
    )
    assertEquals("connectedDevice|microphone", foregroundTypeName(type))
  }
}
