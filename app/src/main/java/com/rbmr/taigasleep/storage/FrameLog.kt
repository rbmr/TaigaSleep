package com.rbmr.taigasleep.storage

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

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
   * Reads frames until the first truncated or CRC-failing one and returns where the good part ends.
   * Invokes [onFrame] with each valid payload.
   */
  fun scan(file: File, onFrame: (ByteArray) -> Unit = {}): Scan {
    val length = file.length()
    if (length < HEADER_SIZE) return Scan(0, 0, 0, length > 0)
    DataIn(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
      val head = ByteArray(HEADER_SIZE.toInt())
      input.readFully(head)
      val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
      val magicOk = MAGIC.indices.all { head[it] == MAGIC[it] }
      if (!magicOk || head[4].toInt() != VERSION) return Scan(0, 0, 0, true)
      val stream = head[5].toInt()
      var pos = HEADER_SIZE
      var frames = 0L
      while (true) {
        if (pos == length) return Scan(stream, frames, pos, false)
        try {
          val len = input.readIntLe()
          if (len < 0 || len > MAX_PAYLOAD || pos + 8 + len > length) {
            return Scan(stream, frames, pos, true)
          }
          val payload = ByteArray(len)
          input.readFully(payload)
          if (input.readIntLe() != crc(payload)) return Scan(stream, frames, pos, true)
          onFrame(payload)
          frames++
          pos += 8 + len
        } catch (_: EOFException) {
          return Scan(stream, frames, pos, true)
        }
      }
    }
  }

  private class DataIn(private val input: BufferedInputStream) : AutoCloseable {
    private val four = ByteArray(4)

    fun readFully(b: ByteArray) {
      var off = 0
      while (off < b.size) {
        val n = input.read(b, off, b.size - off)
        if (n < 0) throw EOFException()
        off += n
      }
    }

    fun readIntLe(): Int {
      readFully(four)
      return ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).int
    }

    override fun close() = input.close()
  }
}

/**
 * Append-only writer for one frame log. Opening an existing file truncates it to the last valid
 * frame first, so a recording interrupted mid-write can be resumed.
 */
class FrameLogWriter(private val file: File, private val stream: Int) : AutoCloseable {
  private val raf: RandomAccessFile
  var frames = 0L
    private set

  var bytes = 0L
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

  /** One `write()` per frame, so a process kill never loses an accepted frame. */
  @Synchronized
  fun append(payload: ByteArray) {
    val frame = FrameLog.frame(payload)
    raf.write(frame)
    frames++
    bytes += frame.size
  }

  @Synchronized
  fun sync() {
    raf.fd.sync()
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
