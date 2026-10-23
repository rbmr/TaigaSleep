package com.rbmr.taigasleep.diagnostics

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records an uncaught exception through [onCrash], then always delegates to the handler that was
 * installed before (on Android that is the one that shows the crash dialog and kills the process).
 */
class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val onCrash: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(thread: Thread, error: Throwable) {
    try {
      onCrash(thread, error)
    } catch (_: Throwable) {
      // Failing to log must not hide the original crash.
    }
    previous?.uncaughtException(thread, error)
  }

  companion object {
    fun install(onCrash: (Thread, Throwable) -> Unit): CrashHandler {
      val handler = CrashHandler(Thread.getDefaultUncaughtExceptionHandler(), onCrash)
      Thread.setDefaultUncaughtExceptionHandler(handler)
      return handler
    }

    /** Full stack trace including "Caused by" chains. */
    fun stackTrace(error: Throwable): String {
      val sw = StringWriter()
      error.printStackTrace(PrintWriter(sw))
      return sw.toString()
    }
  }
}
