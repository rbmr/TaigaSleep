@file:Suppress("ReturnCount", "MaxLineLength", "LoopWithTooManyJumpStatements", "NestedBlockDepth", "TooManyFunctions")

package com.rbmr.taigasleep.tracker

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Plays a recording directory back through the tracker interface. A debug build can record from an
 * existing night, and a test can run the recording path against bytes a real tracker produced.
 *
 * The stream list comes from `meta.json`, so the replay covers exactly what the recording holds.
 * Frames are emitted in the order they are stored; [timed] keeps their spacing for a live replay.
 */
class ReplayTracker(
    private val dir: File,
    private val clock: Clock = SystemClockSource,
    private val timed: Boolean = false,
) : Tracker {
  private val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
  private val sensor = meta["sensor"]?.jsonObject
  override val kind: SensorKind =
      SensorKind.fromWire(sensor?.get("kind")?.jsonPrimitive?.content) ?: SensorKind.SIMULATED
  override val id: String = sensor?.get("id")?.jsonPrimitive?.content ?: "replay"
  override val name: String = "Replay: ${sensor?.get("name")?.jsonPrimitive?.content ?: dir.name}"

  private val entries: List<ReplayStream> = readStreams(meta)
  override val streams: List<OfferedStream> =
      entries.map {
        OfferedStream(
            id = it.id,
            type = it.type,
            codec = it.codec,
            file = it.file,
            ratesHz = it.rateHz?.let { rate -> listOf(rate) } ?: emptyList(),
            order = it.type.ordinal,
        )
      }
  override val capabilities = TrackerCapabilities(clock = false, battery = false, firmware = false)

  private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
  override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
  private val _battery = MutableStateFlow<Int?>(null)
  override val battery: StateFlow<Int?> = _battery.asStateFlow()
  private val _firmware = MutableStateFlow<String?>(null)
  override val firmware: StateFlow<String?> = _firmware.asStateFlow()

  @Volatile private var listener: TrackerListener? = null
  @Volatile private var selected: Set<String> = emptySet()
  private val executor = Executors.newSingleThreadExecutor { Thread(it, "replay-tracker") }

  override fun recordingModes(selected: Set<String>): Set<RecordingMode> =
      if (selected.isEmpty()) emptySet() else setOf(RecordingMode.STREAM)

  override fun connect() {
    _connection.value = ConnectionState.CONNECTED
  }

  override fun disconnect() {
    _connection.value = ConnectionState.DISCONNECTED
  }

  override fun startStreams(selected: Set<String>, listener: TrackerListener) {
    this.selected = selected
    this.listener = listener
    executor.execute { play(listener) }
  }

  override fun stopStreams() {
    selected = emptySet()
    listener = null
  }

  /** Stops the playback thread when the app forgets this tracker. */
  override fun release() {
    executor.shutdownNow()
  }

  /** Emits every stored frame now, without the recording's spacing. */
  fun playAll() {
    val l = listener ?: return
    play(l)
  }

  private fun play(l: TrackerListener) {
    var lastPhoneNs: Long? = null
    for (stream in entries) {
      if (stream.id !in selected) continue
      for (frame in stream.frames) {
        if (timed) {
          lastPhoneNs?.let { previous ->
            val waitMs = ((frame.stamp.phoneNs - previous) / 1_000_000L).coerceAtLeast(0)
            if (waitMs > 0) Thread.sleep(waitMs)
          }
        }
        lastPhoneNs = frame.stamp.phoneNs
        l.onFrame(frame)
      }
    }
  }

  private data class ReplayStream(
      val id: String,
      val type: StreamType,
      val codec: String,
      val file: String,
      val rateHz: Double?,
      val frames: List<TrackerFrame>,
  )

  /** Reads `meta.json`'s stream list, falling back to the old fixed names for old recordings. */
  private fun readStreams(meta: JsonObject): List<ReplayStream> {
    val declared = meta["streams"]?.jsonArray
    val list =
        declared?.mapNotNull { element ->
          val o = element.jsonObject
          val type = StreamType.fromWire(o["type"]?.jsonPrimitive?.content) ?: return@mapNotNull null
          ReplayStream(
              id = o["id"]?.jsonPrimitive?.content ?: type.wire,
              type = type,
              codec = o["codec"]?.jsonPrimitive?.content ?: Codec.POLAR_HR,
              file = o["file"]?.jsonPrimitive?.content ?: "${type.wire}.log",
              rateHz = o["rate_hz"]?.jsonPrimitive?.content?.toDoubleOrNull(),
              frames = emptyList(),
          )
        }
            ?: legacyStreams()
    return list.map { it.copy(frames = readFrames(it)) }
  }

  private fun legacyStreams(): List<ReplayStream> {
    val config = meta["config"]?.jsonObject
    val hr = config?.get("hr")?.jsonPrimitive?.content != "false"
    val ecg = config?.get("ecg")?.jsonPrimitive?.content == "true"
    val acc = config?.get("acc")?.jsonPrimitive?.content == "true"
    return buildList {
      if (hr) {
        add(ReplayStream("hr", StreamType.HEART_RATE, Codec.POLAR_HR, "hr.log", 1.0, emptyList()))
        add(ReplayStream("rr", StreamType.RR_INTERVAL, Codec.POLAR_HR, "hr.log", null, emptyList()))
      }
      if (ecg) add(ReplayStream("ecg", StreamType.ECG, Codec.POLAR_ECG, "ecg.log", 130.0, emptyList()))
      if (acc) {
        add(
            ReplayStream("acc", StreamType.ACCELEROMETER, Codec.POLAR_ACC, "acc.log", 50.0, emptyList())
        )
      }
    }
  }

  private fun readFrames(stream: ReplayStream): List<TrackerFrame> {
    val file = File(dir, stream.file)
    if (!file.isFile) return emptyList()
    val expected = logId(stream.type)
    val frames = ArrayList<TrackerFrame>()
    RandomAccessFile(file, "r").use { raf ->
      if (raf.length() < 8) return emptyList()
      val header = ByteArray(8)
      raf.readFully(header)
      if (String(header, 0, 4) != "TSLG" || header[5].toInt() != expected) return emptyList()
      var offset = 8L
      val length = raf.length()
      while (offset + 8 <= length) {
        raf.seek(offset)
        val len = ByteBuffer.wrap(ByteArray(4).also { raf.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).int
        if (len <= 0 || len > 1 shl 20 || offset + 8 + len > length) break
        val payload = ByteArray(len)
        raf.readFully(payload)
        val crc = ByteArray(4)
        raf.readFully(crc)
        if (ByteBuffer.wrap(crc).order(ByteOrder.LITTLE_ENDIAN).int != crc32(payload)) break
        val phoneNs = ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val elapsedNs = ByteBuffer.wrap(payload, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        frames += TrackerFrame(stream.id, Stamp(phoneNs, elapsedNs), payload)
        offset += 8 + len
      }
    }
    return frames
  }

  private fun crc32(bytes: ByteArray): Int = CRC32().also { it.update(bytes) }.value.toInt()

  private fun logId(type: StreamType): Int =
      when (type) {
        StreamType.HEART_RATE,
        StreamType.RR_INTERVAL -> 1
        StreamType.ECG -> 2
        StreamType.ACCELEROMETER -> 3
        StreamType.PPG -> 4
        StreamType.PULSE_INTERVAL -> 5
        StreamType.GYROSCOPE -> 6
        StreamType.MAGNETOMETER -> 7
      }
}
