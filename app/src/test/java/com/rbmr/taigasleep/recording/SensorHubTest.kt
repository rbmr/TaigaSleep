package com.rbmr.taigasleep.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rbmr.taigasleep.tracker.ConnectionState
import com.rbmr.taigasleep.tracker.FoundSensor
import com.rbmr.taigasleep.tracker.OfferedStream
import com.rbmr.taigasleep.tracker.SensorKind
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerCapabilities
import com.rbmr.taigasleep.tracker.TrackerDriver
import com.rbmr.taigasleep.tracker.TrackerListener
import com.rbmr.taigasleep.tracker.TrackerRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The hub's tracker selection: one tracker per id, and every tracker it drops is released. */
@RunWith(RobolectricTestRunner::class)
class SensorHubTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private val kinds =
      listOf(
          SensorKind.POLAR_H10,
          SensorKind.POLAR_VERITY,
          SensorKind.POLAR_LOOP,
          SensorKind.BLE_HRS,
      )

  /** A tracker a test can follow: it records its state changes and how often it was released. */
  private class ReleasableTracker(
      override val kind: SensorKind,
      override val id: String,
  ) : Tracker {
    override val name = "test tracker"
    override val streams = emptyList<OfferedStream>()
    override val capabilities = TrackerCapabilities(battery = false, firmware = false)

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _battery = MutableStateFlow<Int?>(null)
    override val battery: StateFlow<Int?> = _battery.asStateFlow()
    private val _firmware = MutableStateFlow<String?>(null)
    override val firmware: StateFlow<String?> = _firmware.asStateFlow()

    val releases = AtomicInteger()

    override fun connect() {
      _connection.value = ConnectionState.CONNECTED
    }

    override fun disconnect() {
      _connection.value = ConnectionState.DISCONNECTED
    }

    override fun startStreams(selected: Set<String>, listener: TrackerListener) = Unit

    override fun stopStreams() = Unit

    override fun release() {
      releases.incrementAndGet()
    }
  }

  private fun driver(kind: SensorKind): TrackerDriver =
      object : TrackerDriver {
        override val kind = kind

        override fun matches(device: FoundSensor): Boolean = device.kind == kind

        override fun create(context: Context, device: FoundSensor): Tracker =
            ReleasableTracker(kind, device.id)
      }

  @Test
  fun `the hub releases the tracker of every kind it drops`() {
    val hub = SensorHub(TrackerRegistry(kinds.map(::driver)))

    for (kind in kinds) {
      val tracker = hub.obtain(context, kind, "id-${kind.wire}", "Test tracker") as ReleasableTracker

      assertSame(tracker, hub.sensor.value)
      assertEquals(ConnectionState.CONNECTED, tracker.connection.value)

      hub.clear()

      assertEquals("$kind was not released", 1, tracker.releases.get())
      assertTrue(hub.sensor.value == null)
    }
  }

  @Test
  fun `a replaced tracker is disconnected and released`() {
    val hub = SensorHub(TrackerRegistry(kinds.map(::driver)))

    val first = hub.obtain(context, SensorKind.POLAR_H10, "a", "First") as ReleasableTracker
    val second = hub.obtain(context, SensorKind.POLAR_VERITY, "b", "Second")

    assertNotSame(first, second)
    assertEquals(ConnectionState.DISCONNECTED, first.connection.value)
    assertEquals(1, first.releases.get())
  }

  @Test
  fun `concurrent obtain calls create one tracker`() {
    val created = AtomicInteger()
    val creating = CountDownLatch(1)
    val createMayFinish = CountDownLatch(1)
    val tracker = ReleasableTracker(SensorKind.BLE_HRS, "a")
    val slowDriver =
        object : TrackerDriver {
          override val kind = SensorKind.BLE_HRS

          override fun matches(device: FoundSensor): Boolean = true

          override fun create(context: Context, device: FoundSensor): Tracker {
            created.incrementAndGet()
            creating.countDown()
            createMayFinish.await(5, TimeUnit.SECONDS)
            return tracker
          }
        }
    val hub = SensorHub(TrackerRegistry(listOf(slowDriver)))
    val threads = Executors.newFixedThreadPool(2)
    try {
      val first = threads.submit<Tracker> { hub.obtain(context, SensorKind.BLE_HRS, "a", "A") }
      assertTrue(creating.await(5, TimeUnit.SECONDS))
      val second = threads.submit<Tracker> { hub.obtain(context, SensorKind.BLE_HRS, "a", "A") }
      // The second call has had time to reach obtain and must be waiting for the first one.
      Thread.sleep(200)
      createMayFinish.countDown()

      assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
      assertEquals(1, created.get())
      assertSame(tracker, hub.sensor.value)
    } finally {
      threads.shutdownNow()
    }
  }
}
