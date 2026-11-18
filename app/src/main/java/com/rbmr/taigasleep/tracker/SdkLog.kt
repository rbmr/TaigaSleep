package com.rbmr.taigasleep.tracker

/**
 * The last lines of a driver's own log, for diagnostics when a connect stays pending. The ring is
 * bounded twice — at most [maxLines] lines and [maxChars] characters each — so it cannot grow with
 * a recording. Lines that carry sample bytes are not kept: the ring is for connection state only.
 */
class SdkLog(
    private val clock: Clock = SystemClockSource,
    private val maxLines: Int = 200,
    private val maxChars: Int = 300,
) {
  private class Line(val atMs: Long, val text: String)

  private val lock = Any()
  private val lines = ArrayDeque<Line>()

  fun add(line: String) {
    // The SDK prints notification bytes after "hex:"; health samples never enter the ring.
    if (line.contains("hex:", ignoreCase = true)) return
    val kept = Line(clock.elapsedNs() / NS_PER_MS, line.take(maxChars))
    synchronized(lock) {
      lines.addLast(kept)
      while (lines.size > maxLines) lines.removeFirst()
    }
  }

  /** The kept lines added at or after [sinceMs], oldest first. */
  fun since(sinceMs: Long): List<String> = synchronized(lock) {
    lines.filter { it.atMs >= sinceMs }.map { it.text }
  }

  private companion object {
    const val NS_PER_MS = 1_000_000L
  }
}
