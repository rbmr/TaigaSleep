package com.rbmr.taigasleep.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashHandlerTest {
  @Test
  fun recordsThenChainsToThePreviousHandler() {
    val order = mutableListOf<String>()
    val previous = Thread.UncaughtExceptionHandler { _, _ -> order.add("previous") }
    val handler = CrashHandler(previous) { _, _ -> order.add("record") }
    handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
    assertEquals(listOf("record", "previous"), order)
  }

  @Test
  fun stillChainsWhenRecordingFails() {
    var chained: Throwable? = null
    val error = IllegalStateException("original")
    val handler =
        CrashHandler({ _, e -> chained = e }) { _, _ -> throw OutOfMemoryError("while logging") }
    handler.uncaughtException(Thread.currentThread(), error)
    assertSame(error, chained)
  }

  @Test
  fun worksWithoutAPreviousHandler() {
    var recorded = false
    CrashHandler(null) { _, _ -> recorded = true }
        .uncaughtException(Thread.currentThread(), RuntimeException())
    assertTrue(recorded)
  }

  @Test
  fun stackTraceIncludesCauses() {
    val trace = CrashHandler.stackTrace(RuntimeException("outer", IllegalArgumentException("inner")))
    assertTrue(trace.contains("RuntimeException: outer"))
    assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException: inner"))
  }

  @Test
  fun installReplacesAndRestoresDefaultHandler() {
    val before = Thread.getDefaultUncaughtExceptionHandler()
    try {
      val handler = CrashHandler.install { _, _ -> }
      assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(before)
    }
  }
}
