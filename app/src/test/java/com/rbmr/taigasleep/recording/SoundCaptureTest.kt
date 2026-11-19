package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.TestClock
import com.rbmr.taigasleep.recording.store.FrameLog
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.SoundPayloads
import com.rbmr.taigasleep.recording.store.StreamId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The microphone capture: one frame per second, in the chosen codec, from a scripted microphone. */
class SoundCaptureTest {
  @get:Rule val tmp = TemporaryFolder()
  private val clock = TestClock()

  private fun meta(choice: SoundChoice) =
      RecordingMeta(
          recordingId = "20261023_223000",
          state = RecordingPhase.RECORDING,
          startUtcMs = clock.phoneNs / 1_000_000L,
          endUtcMs = null,
          timezone = "UTC",
          appVersion = "test",
          appVersionCode = 1,
          phoneManufacturer = "JVM",
          phoneModel = "test",
          android = "0",
          sdk = 0,
          sensorKind = "polar-h10",
          sensorId = "SOUND001",
          sensorName = "Test strap",
          sensorFirmware = null,
          sensorBatteryAtStart = 80,
          config = RecordingConfig(hr = true, ecg = false, acc = false),
          streams =
              RecordingConfig(hr = true, ecg = false, acc = false).asStreams() +
                  listOf(choice.stream()!!),
      )

  /** One second of samples the scripted microphone delivers, then silence. */
  private fun oneSecondOfSound(): ByteArray =
      ByteBuffer.allocate(SoundCapture.FRAME_BYTES)
          .order(ByteOrder.LITTLE_ENDIAN)
          .apply { repeat(SoundCapture.FRAME_SAMPLES) { putShort((it % 2000 - 1000).toShort()) } }
          .array()

  private fun soundLog(writer: RecordingWriter) = File(writer.dir, RecordingWriter.SOUND_LOG)

  private fun payloads(writer: RecordingWriter): List<ByteArray> {
    val payloads = ArrayList<ByteArray>()
    FrameLog.scan(soundLog(writer)) { payloads += it }
    return payloads
  }

  private fun events(writer: RecordingWriter) =
      File(writer.dir, "events.jsonl")
          .readLines()
          .map { Json.parseToJsonElement(it).jsonObject }
          .filter { it.getValue("type").jsonPrimitive.content in setOf("sound", "error") }

  @Test
  fun `uncompressed capture writes a second of samples as one sound frame`() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(SoundChoice.UNCOMPRESSED), clock)
    val audio = oneSecondOfSound()
    val capture =
        SoundCapture(
            writer,
            clock,
            SoundChoice.UNCOMPRESSED,
            SoundSourceFactory { FakeSoundSource(audio) },
        )

    try {
      assertTrue(capture.start())
      awaitCondition("the sound frame") { payloads(writer).size == 1 }
    } finally {
      capture.stop()
    }

    val scan = FrameLog.scan(soundLog(writer))
    assertEquals(StreamId.SOUND, scan.stream)
    val frame = SoundPayloads.decodePcm(payloads(writer).single())
    assertEquals(16_000, frame.rateHz)
    assertEquals(SoundCapture.FRAME_SAMPLES, frame.sampleCount)
    assertArrayEquals(audio, frame.pcm)
    writer.close()
  }

  @Test
  fun `compressed capture writes the encoder's packets as one sound frame`() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(SoundChoice.COMPRESSED), clock)
    val packets = listOf(byteArrayOf(0xF8.toByte(), 1, 2), byteArrayOf(0x78, 3))
    val encoder = FakeEncoder(OpusPackets(packets, 960))
    val capture =
        SoundCapture(
            writer,
            clock,
            SoundChoice.COMPRESSED,
            SoundSourceFactory { FakeSoundSource(oneSecondOfSound()) },
            openEncoder = { encoder },
        )

    try {
      assertTrue(capture.start())
      awaitCondition("the sound frame") { payloads(writer).size == 1 }
    } finally {
      capture.stop()
    }

    val frame = SoundPayloads.decodeOpus(payloads(writer).single())
    assertEquals(16_000, frame.rateHz)
    assertEquals(960, frame.sampleCount)
    assertEquals(2, frame.packets.size)
    assertArrayEquals(packets[0], frame.packets[0])
    assertArrayEquals(packets[1], frame.packets[1])
    assertEquals(1, encoder.closes)
    writer.close()
  }

  @Test
  fun `capture start and stop are recorded as sound events`() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(SoundChoice.UNCOMPRESSED), clock)
    val capture =
        SoundCapture(
            writer,
            clock,
            SoundChoice.UNCOMPRESSED,
            SoundSourceFactory { FakeSoundSource(oneSecondOfSound()) },
        )

    try {
      capture.start()
      awaitCondition("the sound frame") { payloads(writer).size == 1 }
    } finally {
      capture.stop()
    }

    val soundEvents = events(writer)
    assertEquals(listOf("sound", "sound"), soundEvents.map { it.getValue("type").jsonPrimitive.content })
    assertTrue(
        soundEvents[0].getValue("message").jsonPrimitive.content.contains("capture started")
    )
    assertTrue(
        soundEvents[1].getValue("message").jsonPrimitive.content.contains("capture stopped")
    )
    writer.close()
  }

  @Test
  fun `no microphone leaves the recording running without sound`() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(SoundChoice.COMPRESSED), clock)
    val capture =
        SoundCapture(writer, clock, SoundChoice.COMPRESSED, SoundSourceFactory { null }) {
          FakeEncoder(OpusPackets(emptyList(), 0))
        }

    assertFalse(capture.start())

    val error = events(writer).single()
    assertEquals("error", error.getValue("type").jsonPrimitive.content)
    assertTrue(error.getValue("message").jsonPrimitive.content.contains("no microphone"))
    assertEquals(0L, FrameLog.scan(soundLog(writer)).frames)
    writer.close()
  }

  @Test
  fun `a microphone that refuses to start is logged and the capture does not run`() {
    val writer = RecordingWriter.create(tmp.newFolder("rec"), meta(SoundChoice.UNCOMPRESSED), clock)
    val source = FakeSoundSource(ByteArray(0)).apply { refuse = true }
    val capture = SoundCapture(writer, clock, SoundChoice.UNCOMPRESSED, SoundSourceFactory { source })

    assertFalse(capture.start())

    val error = events(writer).single()
    assertEquals("error", error.getValue("type").jsonPrimitive.content)
    assertTrue(error.getValue("message").jsonPrimitive.content.contains("capture did not start"))
    assertTrue("the refused microphone is let go", source.released)
    writer.close()
  }

  @Test
  fun `a codec this version does not know reads as sound off`() {
    assertEquals(SoundChoice.OFF, SoundChoice.fromCodec("lossless-v1"))
  }

  /** The microphone as the capture reads it: scripted audio, then silence until the stop. */
  private class FakeSoundSource(private val data: ByteArray) : SoundSource {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var position = 0
    private var stopped = false

    @Volatile var released = false
    @Volatile var refuse = false

    override fun start() {
      check(!refuse) { "the microphone was refused" }
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
        lock.withLock {
          while (position >= data.size && !stopped) drained.await()
          if (position >= data.size) return 0
          val count = minOf(size, data.size - position)
          System.arraycopy(data, position, buffer, offset, count)
          position += count
          count
        }

    override fun stop() {
      lock.withLock {
        stopped = true
        drained.signalAll()
      }
    }

    override fun release() {
      released = true
    }
  }

  private class FakeEncoder(private val packets: OpusPackets) : SoundEncoder {
    var closes = 0
      private set

    override fun encode(pcm: ByteArray, length: Int): OpusPackets = packets

    override fun close() {
      closes++
    }
  }
}
