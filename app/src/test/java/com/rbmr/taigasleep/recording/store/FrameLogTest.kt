package com.rbmr.taigasleep.recording.store

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameLogTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun payload(i: Int) = ByteArray(10 + i) { (i + it).toByte() }

  private fun write(file: File, n: Int) {
    FrameLogWriter(file, StreamId.HR).use { w -> repeat(n) { w.append(payload(it)) } }
  }

  private fun read(file: File): Pair<List<ByteArray>, FrameLog.Scan> {
    val out = mutableListOf<ByteArray>()
    val scan = FrameLog.scan(file) { out += it }
    return out to scan
  }

  @Test
  fun roundTrip() {
    val f = tmp.newFile("hr.log")
    write(f, 20)
    val (frames, scan) = read(f)
    assertEquals(20, frames.size)
    frames.forEachIndexed { i, p -> assertTrue(p.contentEquals(payload(i))) }
    assertFalse(scan.damaged)
    assertEquals(StreamId.HR, scan.stream)
    assertEquals(f.length(), scan.validEnd)
    // Header bytes as specified: TSLG, version 1, stream, reserved.
    val head = f.readBytes().copyOf(8)
    assertEquals("TSLG", String(head, 0, 4))
    assertEquals(1, head[4].toInt())
    assertEquals(1, head[5].toInt())
    assertEquals(0, head[6].toInt() or head[7].toInt())
  }

  @Test
  fun aFailedWriteRollsBackToTheLastGoodFrameAndCountsTheLoss() {
    val f = tmp.newFile("hr.log")
    var failOnce = true
    FrameLogWriter(f, StreamId.HR) { raf, frame ->
          if (failOnce) {
            failOnce = false
            // A full disk can leave part of a frame behind and then refuse the rest.
            raf.write(frame, 0, frame.size / 2)
            throw IOException("no space left on device")
          }
          raf.write(frame)
        }
        .use { w ->
          try {
            w.append(payload(0))
            fail("a failed write must be reported")
          } catch (_: IOException) {}
          assertEquals(1L, w.droppedFrames)
          assertEquals(0L, w.frames)
          w.append(payload(1))
          w.append(payload(2))
          assertEquals(2L, w.frames)
        }
    val (frames, scan) = read(f)
    assertEquals(2, frames.size)
    assertTrue(frames[0].contentEquals(payload(1)))
    assertTrue(frames[1].contentEquals(payload(2)))
    assertFalse(scan.damaged)
  }

  @Test
  fun aPartialWriteThenGoodFramesSurviveAResume() {
    val f = tmp.newFile("hr.log")
    write(f, 3)
    var failOnce = true
    FrameLogWriter(f, StreamId.HR) { raf, frame ->
          if (failOnce) {
            failOnce = false
            raf.write(frame, 0, 1)
            throw IOException("no space left on device")
          }
          raf.write(frame)
        }
        .use { w ->
          try {
            w.append(payload(9))
            fail("a failed write must be reported")
          } catch (_: IOException) {}
          w.append(payload(10))
        }

    FrameLogWriter(f, StreamId.HR).use { w ->
      assertEquals(4L, w.frames)
      w.append(payload(11))
    }
    val (frames, scan) = read(f)
    assertEquals(5, frames.size)
    assertEquals(listOf(0, 1, 2, 10, 11), frames.map { it.first().toInt() and 0xFF })
    assertFalse(scan.damaged)
  }

  @Test
  fun truncatedTailIsIgnoredAndTruncatedOnResume() {
    val f = tmp.newFile("hr.log")
    write(f, 5)
    val full = f.length()
    RandomAccessFile(f, "rw").use { it.setLength(full - 3) }
    val (frames, scan) = read(f)
    assertEquals(4, frames.size)
    assertTrue(scan.damaged)

    // Resuming truncates to the last valid frame, then appends.
    FrameLogWriter(f, StreamId.HR).use { w ->
      assertEquals(4, w.frames)
      w.append(payload(99))
    }
    val (after, scan2) = read(f)
    assertEquals(5, after.size)
    assertTrue(after.last().contentEquals(payload(99)))
    assertFalse(scan2.damaged)
  }

  @Test
  fun aCorruptFrameInTheMiddleIsSkippedAndTheRestIsRead() {
    val f = tmp.newFile("hr.log")
    write(f, 6)
    // Flip a byte inside the third frame's payload.
    val offset = FrameLog.HEADER_SIZE + (0 until 2).sumOf { 8 + payload(it).size } + 4 + 2
    RandomAccessFile(f, "rw").use {
      it.seek(offset)
      val b = it.read()
      it.seek(offset)
      it.write(b xor 0xFF)
    }
    val (frames, scan) = read(f)
    // The damaged frame is gone, but the frames after it are still there.
    assertEquals(5, frames.size)
    assertEquals(listOf(0, 1, 3, 4, 5), frames.map { it.first().toInt() and 0xFF })
    assertTrue(scan.damaged)

    // Resuming keeps those frames instead of truncating at the damage, then appends.
    FrameLogWriter(f, StreamId.HR).use { w ->
      assertEquals(5, w.frames)
      w.append(payload(7))
    }
    val (after, scan2) = read(f)
    assertEquals(6, after.size)
    assertTrue(after.last().contentEquals(payload(7)))
    assertTrue(scan2.damaged)
  }

  @Test
  fun truncatedMidHeaderOfFrame() {
    val f = tmp.newFile("hr.log")
    write(f, 3)
    // Leave only two bytes of a fourth frame's length field.
    f.appendBytes(byteArrayOf(5, 0))
    val (frames, scan) = read(f)
    assertEquals(3, frames.size)
    assertTrue(scan.damaged)
  }

  @Test
  fun absurdLengthIsCorruptionNotAnAllocation() {
    val f = tmp.newFile("hr.log")
    write(f, 2)
    f.appendBytes(byteArrayOf(-1, -1, -1, 0x7F, 1, 2, 3))
    val (frames, scan) = read(f)
    assertEquals(2, frames.size)
    assertTrue(scan.damaged)
  }
}
