package com.rbmr.taigasleep.diagnostics

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only an event that moves a snapshot field pays for a snapshot; the rest only log themselves. */
class EventListenersTest {
  @Test
  fun `screen on and off do not request a snapshot`() {
    assertFalse(EventListeners.changesSnapshot(Intent.ACTION_SCREEN_ON))
    assertFalse(EventListeners.changesSnapshot(Intent.ACTION_SCREEN_OFF))
  }

  @Test
  fun `a Bluetooth change requests a snapshot`() {
    assertTrue(EventListeners.changesSnapshot(BluetoothAdapter.ACTION_STATE_CHANGED))
  }

  @Test
  fun `a clock change requests none because no snapshot field says what time it is`() {
    assertFalse(EventListeners.changesSnapshot(Intent.ACTION_TIME_CHANGED))
    assertTrue(EventListeners.changesSnapshot(Intent.ACTION_TIMEZONE_CHANGED))
  }
}
