package com.rbmr.taigasleep.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** One heartbeat is one read of each phone value it records, not several reads of the same one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceStateTest {
  @Test
  fun `a heartbeat reads the sticky battery intent once`() {
    val context = CountingContext(RuntimeEnvironment.getApplication())

    DeviceState.heartbeat(context)

    assertEquals(1, context.batteryReads)
  }

  /** Counts the sticky battery reads, which are the registerReceiver calls without a receiver. */
  private class CountingContext(base: Context) : ContextWrapper(base) {
    var batteryReads = 0
      private set

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
      if (receiver == null && filter?.hasAction(Intent.ACTION_BATTERY_CHANGED) == true) {
        batteryReads++
      }
      return super.registerReceiver(receiver, filter)
    }
  }
}
