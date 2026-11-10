package com.rbmr.taigasleep.recording.store

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** A point in one frame log: how many bytes and frames it held. */
data class LogPosition(val bytes: Long, val frames: Long)

/** Stream ids of the frame logs (see docs/recording-format.md). */
object StreamId {
  const val HR = 1
  const val ECG = 2
  const val ACC = 3
}

object FrameLog {
  const val VERSION = 1
  const val HEADER_SIZE = 8L

  /** Upper bound for a plausible payload; anything larger is treated as corruption. */
  const val MAX_PAYLOAD = 1 shl 20

  private val MAGIC = byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte(), 'G'.code.toByte())

  fun header(stream: Int): ByteArray =
      ByteBuffer.allocate(HEADER_SIZE.toInt())
          .order(ByteOrder.LITTLE_ENDIAN)
          .put(MAGIC)
          .put(VERSION.toByte())
          .put(stream.toByte())
          .putShort(0)
          .array()

  fun crc(payload: ByteArray): Int = CRC32().also { it.update(payload) }.value.toInt()

  fun frame(payload: ByteArray): ByteArray =
      ByteBuffer.allocate(payload.size + 8)
          .order(ByteOrder.LITTLE_ENDIAN)
          .putInt(payload.size)
          .put(payload)
          .putInt(crc(payload))
          .array()

  /** Result of scanning a log: [validEnd] is the offset just after the last good frame. */
  data class Scan(val stream: Int, val frames: Long, val validEnd: Long, val damagedTail: Boolean)

  /**
   * Reads every frame it can verify. A truncated frame or one whose CRC does not match marks the log
   * damaged; scanning then searches forward for the next offset that holds a whole frame with a
   * matching CRC, so one damaged frame does not hide the frames after it. Invokes [onFrame] with
   * each valid payload.
   *
   * [Scan.validEnd] is the end of the last whole frame, which is where a writer may resume appending
   * without losing anything it kept.
   */
  fun scan(file: File, onFrame: (ByteArray) -> Unit = {}): Scan {
    val length = file.length()
    if (length < HEADER_SIZE) return Scan(0, 0, 0, length > 0)
    RandomAccessFile(file, "r").use { raf ->
      val head = ByteArray(HEADER_SIZE.toInt())
      raf.readFully(head)
      val magicOk = MAGIC.indices.all { head[it] == MAGIC[it] }
      if (!magicOk || head[4].toInt() != VERSION) return Scan(0, 0, 0, true)
      val stream = head[5].toInt()
      val reader = FrameReader(raf)
      var pos = HEADER_SIZE
      var validEnd = HEADER_SIZE
      var frames = 0L
      var damaged = false
      while (pos < length) {
        val payload = reader.frameAt(pos, length)
        if (payload == null) {
          damaged = true
          val next = reader.nextFrameAt(pos + 1, length) ?: return Scan(stream, frames, validEnd, true)
          pos = next
          continue
        }
        onFrame(payload)
        frames++
        pos += 8 + payload.size
        validEnd = pos
      }
      return Scan(stream, frames, validEnd, damaged)
    }
  }

  /**
   * A buffered reader over a [RandomAccessFile] that can start again at any offset. Only the search
   * after damage moves backwards; the normal path reads the buffer straight through.
   */
  private class FrameReader(private val raf: RandomAccessFile) {
    private val buffer = ByteArray(64 * 1024)
    private val four = ByteArray(4)
    private var bufferStart = 0L
    private var bufferCount = 0
    private var position = 0L

    /** The payload of a whole frame starting at [offset], or null when it is not one. */
    fun frameAt(offset: Long, length: Long): ByteArray? {
      if (offset + 8 > length) return null
      position = offset
      val len = readIntLe() ?: return null
      if (len <= 0 || len > MAX_PAYLOAD || offset + 8 + len > length) return null
      val payload = ByteArray(len)
      if (!readFully(payload)) return null
      if (readIntLe() != crc(payload)) return null
      return payload
    }

    /** The offset of the first whole frame at or after [from], or null when there is none. */
    fun nextFrameAt(from: Long, length: Long): Long? {
      var offset = from
      while (offset + 8 <= length) {
        if (frameAt(offset, length) != null) return offset
        offset++
      }
      return null
    }

    private fun readFully(target: ByteArray): Boolean {
      var off = 0
      while (off < target.size) {
        if (!buffered()) return false
        val index = (position - bufferStart).toInt()
        val n = minOf(bufferCount - index, target.size - off)
        System.arraycopy(buffer, index, target, off, n)
        position += n
        off += n
      }
      return true
    }

    private fun readIntLe(): Int? {
      if (!readFully(four)) return null
      return ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** Fills the buffer from [position] when it does not hold that byte yet. */
    private fun buffered(): Boolean {
      if (position >= bufferStart && position < bufferStart + bufferCount) return true
      raf.seek(position)
      val n = raf.read(buffer)
      bufferStart = position
      bufferCount = if (n > 0) n else 0
      return n > 0
    }
  }
}

/**
 * Append-only writer for one frame log. Opening an existing file truncates it to the last valid
 * frame first, so a recording interrupted mid-write can be resumed.
 *
 * [writeFrame] is the raw write and exists so a test can make one fail part-way through; production
 * code uses it as it is.
 */
class FrameLogWriter
internal constructor(
    private val file: File,
    private val stream: Int,
    private val writeFrame: (RandomAccessFile, ByteArray) -> Unit = { raf, frame -> raf.write(frame) },
) : AutoCloseable {
  private val raf: RandomAccessFile
  var frames = 0L
    private set

  var bytes = 0L
    private set

  /** Frames a failed write cost, so the recording can account for the gap. */
  var droppedFrames = 0L
    private set

  init {
    val existing = if (file.exists()) FrameLog.scan(file) else null
    raf = RandomAccessFile(file, "rw")
    try {
      if (existing == null || existing.validEnd == 0L) {
        raf.setLength(0)
        raf.write(FrameLog.header(stream))
      } else {
        if (existing.stream != stream) throw IOException("${file.name}: wrong stream ${existing.stream}")
        raf.setLength(existing.validEnd)
        frames = existing.frames
      }
      raf.seek(raf.length())
      bytes = raf.length()
    } catch (e: IOException) {
      raf.close()
      throw e
    }
  }

  /**
   * One `write()` per frame, so a process kill never loses an accepted frame.
   *
   * A failed write has already moved the file position, so the log is set back to the last whole
   * frame before the error is rethrown: the next append continues from good data instead of behind
   * the damaged bytes, and the lost frame is counted.
   */
  @Synchronized
  fun append(payload: ByteArray) {
    val frame = FrameLog.frame(payload)
    try {
      writeFrame(raf, frame)
      frames++
      bytes += frame.size
    } catch (e: IOException) {
      droppedFrames++
      try {
        raf.setLength(bytes)
      } catch (_: IOException) {}
      try {
        raf.seek(bytes)
      } catch (_: IOException) {}
      throw e
    }
  }

  @Synchronized
  fun sync() {
    raf.fd.sync()
  }

  /** Truncates the log back to a position taken before an append. */
  @Synchronized
  fun truncate(position: LogPosition) {
    raf.setLength(position.bytes)
    raf.seek(position.bytes)
    frames = position.frames
    bytes = position.bytes
  }

  @Synchronized
  override fun close() {
    try {
      raf.fd.sync()
    } finally {
      raf.close()
    }
  }
}
