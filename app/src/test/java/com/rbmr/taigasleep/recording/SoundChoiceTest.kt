package com.rbmr.taigasleep.recording

import com.rbmr.taigasleep.tracker.Codec
import com.rbmr.taigasleep.tracker.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each sound choice names the `sound` stream of `docs/recording-format.md` it adds. */
class SoundChoiceTest {
  @Test
  fun `compressed adds the Opus sound stream`() {
    val stream = SoundChoice.COMPRESSED.stream()!!

    assertEquals("sound", stream.id)
    assertEquals(StreamType.SOUND, stream.type)
    assertEquals(Codec.SOUND_OPUS, stream.codec)
    assertEquals("sound.log", stream.file)
    assertEquals(16_000.0, stream.rateHz)
  }

  @Test
  fun `uncompressed adds the PCM sound stream`() {
    val stream = SoundChoice.UNCOMPRESSED.stream()!!

    assertEquals(StreamType.SOUND, stream.type)
    assertEquals(Codec.SOUND_PCM, stream.codec)
    assertEquals("sound.log", stream.file)
    assertEquals(16_000.0, stream.rateHz)
  }

  @Test
  fun `off adds no stream`() {
    assertNull(SoundChoice.OFF.stream())
  }

  @Test
  fun `a stored choice names its wire value and is read back`() {
    assertEquals(SoundChoice.COMPRESSED, SoundChoice.fromWire("compressed"))
    assertEquals(SoundChoice.UNCOMPRESSED, SoundChoice.fromWire("uncompressed"))
    assertEquals(SoundChoice.OFF, SoundChoice.fromWire("off"))
    assertEquals(SoundChoice.OFF, SoundChoice.fromWire(null))
  }
}
