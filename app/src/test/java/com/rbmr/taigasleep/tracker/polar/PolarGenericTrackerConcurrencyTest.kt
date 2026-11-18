package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.polar.sdk.api.model.PolarHrData
import com.rbmr.taigasleep.tracker.Tracker
import com.rbmr.taigasleep.tracker.TrackerFrame
import com.rbmr.taigasleep.tracker.TrackerListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The generic driver hears its SDK callbacks and its caller's start, stop and disconnect on
 * different threads. A storm of both must leave no stream collecting and nothing thrown.
 */
@RunWith(RobolectricTestRunner::class)
class PolarGenericTrackerConcurrencyTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  private class QuietListener : TrackerListener {
    val errors = CopyOnWriteArrayList<String>()

    override fun onFrame(frame: TrackerFrame) = Unit

    override fun onError(message: String) {
      errors += message
    }
  }

  @Test
  fun `connect and disconnect callbacks racing start and stop leave no stream behind`() {
    val threads = Executors.newFixedThreadPool(4)
    try {
      // A few hundred rounds: the callbacks and the caller only interleave badly now and then, so
      // the loop is the test and one round proves little.
      repeat(ROUNDS) { raceOnce(threads) }
    } finally {
      threads.shutdownNow()
    }
  }

  /**
   * One round: one stream starts, then two threads fire the seam's callbacks while two drive the
   * tracker.
   */
  private fun raceOnce(threads: ExecutorService) {
    val collecting = AtomicInteger()
    val started = CountDownLatch(1)
    val seam = FakePolarSeam().apply { hrData = endlessHr(collecting, started) }
    val tracker = PolarVerityTracker(context, "V1", "Polar Verity Sense", seam)
    val sdk = requireNotNull(seam.listener("V1"))
    val listener = QuietListener()
    try {
      sdk.onConnected(null)
      tracker.startStreams(setOf("hr"), listener)
      assertTrue("the stream did not start", started.await(ROUND_TIMEOUT_S, TimeUnit.SECONDS))
      val tasks =
          listOf(
              threads.submit { fireCallbacks(sdk) },
              threads.submit { fireDisconnects(sdk) },
              threads.submit { startAndStop(tracker, listener) },
              threads.submit { disconnectAndConnect(tracker) },
          )
      tasks.forEach { it.get(ROUND_TIMEOUT_S, TimeUnit.SECONDS) }
    } finally {
      tracker.stopStreams()
      awaitNoCollectors(collecting)
      assertEquals("a stream reported an error", emptyList<String>(), listener.errors)
      tracker.release()
    }
  }

  private fun fireCallbacks(sdk: PolarSeam.ConnectionListener) {
    repeat(OPS) {
      sdk.onConnected(null)
      sdk.onDisconnected()
    }
  }

  private fun fireDisconnects(sdk: PolarSeam.ConnectionListener) {
    repeat(OPS) { sdk.onDisconnected() }
  }

  private fun startAndStop(tracker: Tracker, listener: TrackerListener) {
    repeat(OPS) {
      tracker.startStreams(setOf("hr"), listener)
      tracker.stopStreams()
    }
  }

  private fun disconnectAndConnect(tracker: Tracker) {
    repeat(OPS) {
      tracker.disconnect()
      tracker.connect()
    }
  }

  /** A heart rate stream that keeps collecting until it is cancelled. */
  private fun endlessHr(collecting: AtomicInteger, started: CountDownLatch): Flow<PolarHrData> =
      flow {
        collecting.incrementAndGet()
        started.countDown()
        try {
          awaitCancellation()
        } finally {
          collecting.decrementAndGet()
        }
      }

  /** Cancellation reaches a collector a moment after the stop that asked for it. */
  private fun awaitNoCollectors(collecting: AtomicInteger) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUND_TIMEOUT_S)
    while (collecting.get() != 0) {
      assertTrue("a stream was still collecting after stopStreams", System.nanoTime() < deadline)
      Thread.yield()
    }
  }

  private companion object {
    const val ROUNDS = 300
    const val OPS = 10
    const val ROUND_TIMEOUT_S = 10L
  }
}
