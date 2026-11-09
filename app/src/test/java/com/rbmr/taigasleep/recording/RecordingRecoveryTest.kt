package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rules that keep a recording alive: what opening the app does with a marked recording, what an
 * upload does when it finds one open, and what the boot broadcast picks up again.
 */
class RecordingRecoveryTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun store() = RecordingStore(tmp.newFolder())

  private fun open(
      store: RecordingStore,
      id: String = "20261023_223000",
      mode: RecordingMode = RecordingMode.STREAM,
  ): File =
      RecordingWriter.create(
              store.root,
              RecordingMeta(
                  recordingId = id,
                  state = RecordingPhase.RECORDING,
                  mode = mode,
                  startUtcMs = clock.phoneNs / 1_000_000L,
                  endUtcMs = null,
                  timezone = "Europe/Amsterdam",
                  appVersion = "0.1.0",
                  appVersionCode = 1,
                  phoneManufacturer = "Test",
                  phoneModel = "T1",
                  android = "16",
                  sdk = 36,
                  sensorKind = "polar-h10",
                  sensorId = "069A4737",
                  sensorName = "Polar H10 069A4737",
                  sensorFirmware = "3.0.35",
                  sensorBatteryAtStart = 87,
                  config = RecordingConfig(ecg = true, acc = true),
              ),
              clock,
          )
          .dir

  private fun state(dir: File) = RecordingMeta.read(dir)!!.state

  @Test
  fun `opening the app resumes a marked recording instead of orphaning it`() {
    val store = store()
    val dir = open(store)

    val live = RecordingRecovery.onAppear(store, "20261023_223000")

    assertEquals("20261023_223000", live?.recordingId)
    // Nothing on disk was touched: the service decides whether the recording can carry on.
    assertEquals(RecordingPhase.RECORDING, state(dir))
  }

  @Test
  fun `opening the app orphans a recording without a marker`() {
    val store = store()
    val dir = open(store)

    assertNull(RecordingRecovery.onAppear(store, null))

    assertEquals(RecordingPhase.INTERRUPTED, state(dir))
  }

  @Test
  fun `opening the app does not resume a recording whose marker points at nothing`() {
    val store = store()
    val dir = open(store)

    assertNull(RecordingRecovery.onAppear(store, "gone"))

    assertEquals(RecordingPhase.INTERRUPTED, state(dir))
  }

  @Test
  fun `a recording the tracker stores is not resumed and stays active`() {
    val store = store()
    val dir = open(store, mode = RecordingMode.ON_DEVICE)

    assertNull(RecordingRecovery.onAppear(store, "20261023_223000"))

    // The tracker keeps recording without the phone, so app start leaves it open.
    assertEquals(RecordingPhase.RECORDING, state(dir))
  }

  @Test
  fun `an upload leaves the recording the phone still marks live to the service`() {
    assertEquals(
        RecoveryAction.RESUME,
        RecordingRecovery.onUploadFoundOpen(RecordingMode.STREAM, "r1", "r1"),
    )
  }

  @Test
  fun `an upload closes a recording without a marker`() {
    assertEquals(
        RecoveryAction.INTERRUPT,
        RecordingRecovery.onUploadFoundOpen(RecordingMode.STREAM, null, "r1"),
    )
    assertEquals(
        RecoveryAction.INTERRUPT,
        RecordingRecovery.onUploadFoundOpen(RecordingMode.STREAM, "other", "r1"),
    )
  }

  @Test
  fun `an upload waits for a recording the tracker is storing`() {
    assertEquals(
        RecoveryAction.WAIT,
        RecordingRecovery.onUploadFoundOpen(RecordingMode.ON_DEVICE, null, "r1"),
    )
  }
}
