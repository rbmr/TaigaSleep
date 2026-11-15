package com.rbmr.taigasleep.recording

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a boot does: the resume decision is made off the broadcast thread, and the receiver keeps its
 * pending result open until the work is done. The recording it hands back is the same one as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {
  @get:Rule val tmp = TemporaryFolder()
  private val context: Context = RuntimeEnvironment.getApplication()
  private val clock = TestClock()

  private fun store() = RecordingStore(tmp.newFolder())

  private fun recording(
      store: RecordingStore,
      id: String = "20261023_223000",
      state: RecordingPhase = RecordingPhase.RECORDING,
  ): File =
      RecordingWriter.create(
              store.root,
              RecordingMeta(
                  recordingId = id,
                  state = state,
                  mode = RecordingMode.STREAM,
                  startUtcMs = clock.phoneNs / 1_000_000L,
                  endUtcMs = null,
                  timezone = "Europe/Amsterdam",
                  appVersion = "0.7.52",
                  appVersionCode = 7052,
                  phoneManufacturer = "Test",
                  phoneModel = "T1",
                  android = "16",
                  sdk = 36,
                  sensorKind = "polar-h10",
                  sensorId = "069A4737",
                  sensorName = "Polar H10 069A4737",
                  sensorFirmware = "3.0.35",
                  sensorBatteryAtStart = 87,
                  config = RecordingConfig(ecg = false, acc = false),
              ),
              clock,
          )
          .dir

  @Test
  fun `the boot work runs off the broadcast and the receiver finishes its pending result`() {
    val worked = mutableListOf<Context>()
    var onMainThread = true
    val receiver =
        BootReceiver { booted ->
          worked += booted
          onMainThread = Looper.myLooper() == Looper.getMainLooper()
        }
    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED))
    try {
      context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
      shadowOf(Looper.getMainLooper()).idle()

      assertTrue("the receiver asked for more time", shadowOf(receiver).wentAsync())
      val pending = shadowOf(receiver).originalPendingResult
      // The work runs on the receiver's thread, and finish() is what releases the process.
      shadowOf(pending).future.get(5, TimeUnit.SECONDS)

      assertEquals(listOf<Context>(context), worked)
      assertFalse("the decision runs off the main thread", onMainThread)
    } finally {
      context.unregisterReceiver(receiver)
    }
  }

  @Test
  fun `a broadcast that is not the boot does nothing`() {
    val worked = mutableListOf<Context>()
    val receiver = BootReceiver { booted -> worked += booted }

    receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))

    assertFalse(shadowOf(receiver).wentAsync())
    assertTrue(worked.isEmpty())
  }

  @Test
  fun `the marked recording is logged and handed to the service`() {
    val store = store()
    recording(store)
    val logged = mutableListOf<String>()
    var resumed = 0

    resumeAfterBoot(
        store,
        "20261023_223000",
        log = { logged += it },
        resume = { resumed++ },
    )

    assertEquals(listOf("20261023_223000"), logged)
    assertEquals(1, resumed)
  }

  @Test
  fun `a boot that interrupted no recording resumes nothing`() {
    val store = store()
    recording(store)
    val logged = mutableListOf<String>()
    var resumed = 0

    // No marker: nothing was running when the phone rebooted.
    resumeAfterBoot(store, null, log = { logged += it }, resume = { resumed++ })

    assertTrue(logged.isEmpty())
    assertEquals(0, resumed)
  }

  @Test
  fun `a marker that names a finished recording resumes nothing`() {
    val store = store()
    recording(store, state = RecordingPhase.STOPPED)
    val logged = mutableListOf<String>()
    var resumed = 0

    resumeAfterBoot(
        store,
        "20261023_223000",
        log = { logged += it },
        resume = { resumed++ },
    )

    assertTrue(logged.isEmpty())
    assertEquals(0, resumed)
  }
}
