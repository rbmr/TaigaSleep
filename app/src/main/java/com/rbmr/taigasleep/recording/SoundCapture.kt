@file:Suppress("ReturnCount", "TooGenericExceptionCaught")

package com.rbmr.taigasleep.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.rbmr.taigasleep.diagnostics.Diagnostics
import com.rbmr.taigasleep.recording.store.SoundPayloads
import com.rbmr.taigasleep.tracker.Clock
import com.rbmr.taigasleep.tracker.Stamp
import com.rbmr.taigasleep.tracker.TrackerFrame

/** The phone's microphone, as [SoundCapture] uses it. A test supplies scripted audio instead. */
interface SoundSource {
  fun start()

  /** Waits for up to [size] bytes and returns how many it wrote; 0 when none are ready yet. */
  fun read(buffer: ByteArray, offset: Int, size: Int): Int

  /** Stops capture and lets a blocked [read] return. */
  fun stop()

  fun release()
}

/** Opens the microphone a recording captures from; null when Android will not give one. */
fun interface SoundSourceFactory {
  fun open(): SoundSource?
}

/** The microphone capture of a live session, as the control flow starts and stops it. */
interface SoundRecorder {
  /** Starts capturing; false when the microphone refused, with the reason in the recording events. */
  fun start(): Boolean

  /** Stops capturing and stores what was read; safe to call more than once. */
  fun stop()
}

/**
 * Records the phone's microphone as the `sound` stream: mono 16-bit PCM at 16 kHz, handed to the
 * writer one frame per second. It is not part of a tracker driver. The recording's stream list says
 * whether it runs, and the recording service starts and stops it with the tracker's streams.
 *
 * A microphone that will not start (no permission, or refused from the background) is an event and
 * not a failed recording: the rest of the night goes on.
 */
class SoundCapture(
    private val writer: RecordingWriter,
    private val clock: Clock,
    private val choice: SoundChoice,
    private val openSource: SoundSourceFactory = SoundSourceFactory { AudioRecordSource.open() },
    private val openEncoder: (Int) -> SoundEncoder? = { OpusEncoder.open(it) },
) : SoundRecorder {
  private val thread = Thread(::capture, "sound-capture")
  @Volatile private var source: SoundSource? = null
  @Volatile private var encoder: SoundEncoder? = null
  @Volatile private var running = false

  override fun start(): Boolean {
    if (running) return true
    val source = openSource.open()
    if (source == null) {
      failed("no microphone")
      return false
    }
    val encoder = if (choice == SoundChoice.COMPRESSED) openEncoder(SAMPLE_RATE_HZ) else null
    if (choice == SoundChoice.COMPRESSED && encoder == null) {
      source.release()
      failed("the Opus encoder is not available")
      return false
    }
    try {
      source.start()
    } catch (e: Exception) {
      runCatching { source.release() }
      runCatching { encoder?.close() }
      failed(reasonOf(e))
      return false
    }
    this.source = source
    this.encoder = encoder
    running = true
    writer.event("sound", "sound capture started: ${choice.wire}, ${SAMPLE_RATE_HZ / 1000} kHz")
    Diagnostics.log(
        "sound_capture",
        mapOf(
            "recording" to writer.meta.recordingId,
            "state" to "started",
            "codec" to choice.wire,
        ),
    )
    thread.start()
    return true
  }

  override fun stop() {
    if (!running) {
      closeResources()
      return
    }
    running = false
    // The read returns once capture stops, so the thread ends without waiting out its frame.
    runCatching { source?.stop() }
    try {
      thread.join(STOP_TIMEOUT_MS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    closeResources()
    writer.event("sound", "sound capture stopped")
    Diagnostics.log(
        "sound_capture",
        mapOf("recording" to writer.meta.recordingId, "state" to "stopped"),
    )
  }

  /** Lets the microphone and the encoder go once, whichever path ends the capture. */
  @Synchronized
  private fun closeResources() {
    val source = source
    val encoder = encoder
    source?.let { runCatching { it.release() } }
    encoder?.let { runCatching { it.close() } }
    this.source = null
    this.encoder = null
  }

  /** Fills one frame at a time; a partial frame at the stop is stored too. */
  private fun capture() {
    val frame = ByteArray(FRAME_BYTES)
    var filled = 0
    var stamp = Stamp.now(clock)
    while (running) {
      val source = source ?: return
      val read =
          try {
            source.read(frame, filled, frame.size - filled)
          } catch (e: Exception) {
            fail("the microphone read failed: ${reasonOf(e)}")
            return
          }
      if (read < 0) {
        fail("the microphone read failed")
        return
      }
      if (read == 0) continue
      filled += read
      stamp = Stamp.now(clock)
      if (filled == frame.size) {
        emit(frame, filled, stamp)
        filled = 0
      }
    }
    if (filled > 0) emit(frame, filled, stamp)
  }

  /** One frame's payload in the chosen codec, handed off so the capture never waits for the disk. */
  private fun emit(pcm: ByteArray, length: Int, stamp: Stamp) {
    try {
      val payload =
          when (choice) {
            SoundChoice.OFF -> return
            SoundChoice.UNCOMPRESSED ->
                SoundPayloads.encodePcm(stamp.phoneNs, stamp.elapsedNs, SAMPLE_RATE_HZ, pcm, length)
            SoundChoice.COMPRESSED -> encodeOpus(pcm, length, stamp) ?: return
          }
      writer.handOff(TrackerFrame(SoundChoice.SOUND_STREAM_ID, stamp, payload.bytes))
    } catch (e: Exception) {
      // A frame the encoder refuses must not take the microphone down without a word.
      fail("the sound frame could not be stored: ${reasonOf(e)}")
    }
  }

  /**
   * The packets the encoder has ready. A chunk whose packets have not come out yet stores nothing
   * now: the encoder keeps them for the next frame, so no audio is lost, only its frame moves.
   */
  private fun encodeOpus(pcm: ByteArray, length: Int, stamp: Stamp) =
      encoder?.encode(pcm, length)?.takeIf { it.packets.isNotEmpty() }?.let {
        SoundPayloads.encodeOpus(
            stamp.phoneNs,
            stamp.elapsedNs,
            SAMPLE_RATE_HZ,
            it.samples,
            it.packets,
        )
      }

  private fun fail(message: String) {
    running = false
    Log.w(TAG, message)
    writer.event("error", "sound capture failed: $message")
    Diagnostics.log(
        "sound_capture",
        mapOf("recording" to writer.meta.recordingId, "state" to "failed", "error" to message),
    )
    closeResources()
  }

  /** A start that did not happen: the recording goes on without sound. */
  private fun failed(reason: String) {
    Log.w(TAG, "sound capture did not start: $reason")
    writer.event("error", "sound capture did not start: $reason")
    Diagnostics.log(
        "sound_capture",
        mapOf(
            "recording" to writer.meta.recordingId,
            "state" to "failed",
            "codec" to choice.wire,
            "error" to reason,
        ),
    )
  }

  private fun reasonOf(e: Exception): String = e.message ?: e.javaClass.simpleName

  companion object {
    private const val TAG = "SoundCapture"

    /** The rate the microphone is opened at and the payloads declare. */
    const val SAMPLE_RATE_HZ = SoundPayloads.RATE_HZ

    /** One second of mono 16-bit samples, the size of one frame. */
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ

    private const val CHANNEL_BYTES = 2
    internal const val FRAME_BYTES = FRAME_SAMPLES * CHANNEL_BYTES
    private const val STOP_TIMEOUT_MS = 2_000L
  }
}

/** The microphone through [AudioRecord]; opening it needs `RECORD_AUDIO`. */
internal class AudioRecordSource private constructor(private val record: AudioRecord) : SoundSource {
  override fun start() {
    record.startRecording()
    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      "the microphone did not start"
    }
  }

  override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
      record.read(buffer, offset, size)

  override fun stop() {
    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
  }

  override fun release() = record.release()

  companion object {
    private const val TAG = "SoundCapture"

    fun open(): SoundSource? {
      val minimum =
          AudioRecord.getMinBufferSize(
              SoundCapture.SAMPLE_RATE_HZ,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
          )
      if (minimum <= 0) {
        Log.w(TAG, "the microphone does not support 16 kHz mono")
        return null
      }
      val format =
          AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(SoundCapture.SAMPLE_RATE_HZ)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .build()
      val record =
          try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, SoundCapture.FRAME_SAMPLES * 2))
                .build()
          } catch (e: Exception) {
            Log.w(TAG, "the microphone is not available", e)
            return null
          }
      if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        Log.w(TAG, "the microphone could not be prepared")
        return null
      }
      return AudioRecordSource(record)
    }
  }
}
