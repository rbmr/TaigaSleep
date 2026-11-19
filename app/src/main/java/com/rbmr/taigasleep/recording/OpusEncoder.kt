package com.rbmr.taigasleep.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.rbmr.taigasleep.recording.store.SoundPayloads

/** What one encode call produced: the packets that were ready and the samples they decode to. */
class OpusPackets(val packets: List<ByteArray>, val samples: Int)

/**
 * Turns chunks of mono PCM into Opus packets. [OpusEncoder] is the phone's encoder; a test scripts
 * the packets instead.
 */
interface SoundEncoder : AutoCloseable {
  fun encode(pcm: ByteArray, length: Int): OpusPackets
}

/**
 * The phone's Opus encoder behind `MediaCodec` (`audio/opus`, API 29 and later). Each call submits
 * one chunk of mono PCM and returns the packets the encoder produced, in order. A packet that comes
 * out a moment after its input is kept for the next call rather than dropped, so a frame holds what
 * the encoder had ready and the next one holds the rest.
 */
internal class OpusEncoder private constructor(
    private val codec: MediaCodec,
    private val rateHz: Int,
) : SoundEncoder {
  private val pending = ArrayList<ByteArray>()
  private var pendingSamples = 0
  private var submittedSamples = 0L

  override fun encode(pcm: ByteArray, length: Int): OpusPackets {
    val index = codec.dequeueInputBuffer(TIMEOUT_US)
    if (index >= 0) {
      val input = codec.getInputBuffer(index)
      if (input != null) {
        input.clear()
        input.put(pcm, 0, length)
        codec.queueInputBuffer(index, 0, length, submittedSamples * MICROSECONDS / rateHz, 0)
        submittedSamples += length / 2
      }
    }
    drain()
    return take()
  }

  private fun drain() {
    val info = MediaCodec.BufferInfo()
    while (true) {
      val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
      if (index < 0) return
      // The first output of an encoder carries the codec configuration, not audio.
      val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
      if (!config && info.size > 0) {
        val output = codec.getOutputBuffer(index)
        if (output != null) {
          output.position(info.offset)
          output.limit(info.offset + info.size)
          val packet = ByteArray(info.size)
          output.get(packet)
          pending += packet
          pendingSamples += samplesAt(packet, rateHz)
        }
      }
      codec.releaseOutputBuffer(index, false)
    }
  }

  private fun take(): OpusPackets {
    if (pending.isEmpty()) return OpusPackets(emptyList(), 0)
    val result = OpusPackets(pending.toList(), pendingSamples)
    pending.clear()
    pendingSamples = 0
    return result
  }

  override fun close() {
    runCatching { codec.stop() }
    codec.release()
  }

  companion object {
    private const val TAG = "OpusEncoder"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
    private const val MICROSECONDS = 1_000_000L
    private const val TIMEOUT_US = 10_000L

    /** The phone's encoder, or null when it has none or refuses to start. */
    @Suppress("TooGenericExceptionCaught")
    fun open(rateHz: Int): OpusEncoder? {
      var codec: MediaCodec? = null
      return try {
        val format =
            MediaFormat.createAudioFormat(MIME, rateHz, 1).apply {
              setInteger(MediaFormat.KEY_BIT_RATE, SoundPayloads.OPUS_BIT_RATE_BPS)
              setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rateHz * 2)
            }
        codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        OpusEncoder(codec, rateHz)
      } catch (e: Exception) {
        Log.e(TAG, "the Opus encoder is not available", e)
        runCatching { codec?.release() }
        null
      }
    }

    /**
     * How many samples one Opus packet decodes to at [rateHz], read from its table-of-contents byte:
     * the config names the frame duration, the low two bits how many frames the packet holds.
     */
    internal fun samplesAt(packet: ByteArray, rateHz: Int): Int {
      if (packet.isEmpty()) return 0
      val toc = packet[0].toInt() and 0xFF
      val frames =
          when (toc and 0x03) {
            0 -> 1
            1,
            2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
          }
      val samples48k = FRAME_SAMPLES_AT_48K[toc shr 3]
      return (samples48k * frames.toLong() * rateHz / RATE_48K).toInt().coerceAtLeast(1)
    }

    private const val RATE_48K = 48_000L

    /** One frame's duration in 48 kHz samples for each Opus config (RFC 6716, section 3.1). */
    private val FRAME_SAMPLES_AT_48K =
        intArrayOf(
            480, 960, 1920, 2880, 480, 960, 1920, 2880,
            480, 960, 1920, 2880, 480, 960, 480, 960,
            120, 240, 480, 960, 120, 240, 480, 960,
            120, 240, 480, 960, 120, 240, 480, 960,
        )
  }
}
