package com.rbmr.taigasleep.recording.store

import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

enum class RecordingPhase(val wire: String) {
  RECORDING("recording"),
  STOPPED("stopped"),
  FORCED_STOP("forced_stop"),
  INTERRUPTED("interrupted"),
  /** The tracker still holds part of the night; fetching it again is the only way to finish. */
  FETCH_PENDING("fetch_pending");

  /**
   * The recording is not finished: its streams may still grow, so nothing may upload it or remove
   * its raw files. A recording becomes finished once the recording stops, and a [FETCH_PENDING] one
   * once the rest of it has arrived from the tracker.
   */
  val unfinished: Boolean
    get() = this == RECORDING || this == FETCH_PENDING

  companion object {
    fun fromWire(value: String?): RecordingPhase =
        entries.firstOrNull { it.wire == value } ?: INTERRUPTED
  }
}

data class RecordingConfig(
    val hr: Boolean = true,
    val ecg: Boolean,
    val acc: Boolean,
    val ecgRateHz: Int = 130,
    val accRateHz: Int = 50,
    val accRangeG: Int = 8,
) {
  fun streams(): List<Int> =
      buildList {
        if (hr) add(StreamId.HR)
        if (ecg) add(StreamId.ECG)
        if (acc) add(StreamId.ACC)
      }
}

data class RecordingMeta(
    val recordingId: String,
    val state: RecordingPhase,
    /** Streamed to the phone, or made on the tracker and fetched at the end. */
    val mode: RecordingMode = RecordingMode.STREAM,
    val startUtcMs: Long,
    val endUtcMs: Long?,
    val timezone: String,
    val appVersion: String,
    val appVersionCode: Int,
    val phoneManufacturer: String,
    val phoneModel: String,
    val android: String,
    val sdk: Int,
    val sensorKind: String,
    val sensorId: String,
    val sensorName: String,
    val sensorFirmware: String?,
    val sensorBatteryAtStart: Int?,
    val config: RecordingConfig,
    /** The tracker still holds a part of this recording that is not on the phone. */
    val fetchPending: Boolean = false,
    /** How many samples of each tracker entry are already imported, keyed by its id. */
    val importedEntries: Map<String, Int> = emptyMap(),
    /** The entry whose samples are being appended now; a restart rolls it back before retrying. */
    val importStarted: String? = null,
    /** The log positions from before [importStarted] began, keyed by stream id. */
    val importStart: Map<Int, LogPosition> = emptyMap(),
    /** Tracker entries stored but not removed yet, retried when the tracker is next connected. */
    val trackerRemovalPending: List<String> = emptyList(),
    /** How many tracker connections in a row could not remove [trackerRemovalPending]. */
    val trackerRemovalFailures: Int = 0,
) {
  /** meta.json as this version writes it: every key of the format, null where there is no value. */
  fun toJson(): JsonObject {
    val onDevice =
        if (mode != RecordingMode.ON_DEVICE) null
        else
            OnDeviceJson(
                fetchPending = fetchPending,
                importedEntries = importedEntries,
                importStarted = importStarted,
                importStart =
                    importStart.mapKeys { it.key.toString() }.mapValues {
                      PositionJson(bytes = it.value.bytes, frames = it.value.frames)
                    },
                trackerRemovalPending = trackerRemovalPending,
                trackerRemovalFailures = trackerRemovalFailures,
            )
    val json =
        metaJson
            .encodeToJsonElement(
                MetaJson(
                    sessionId = recordingId,
                    state = state.wire,
                    mode = mode.wire,
                    startUtcMs = startUtcMs,
                    endUtcMs = endUtcMs,
                    timezone = timezone,
                    app = AppJson(version = appVersion, versionCode = appVersionCode),
                    phone =
                        PhoneJson(
                            manufacturer = phoneManufacturer,
                            model = phoneModel,
                            android = android,
                            sdk = sdk,
                        ),
                    sensor =
                        SensorJson(
                            kind = sensorKind,
                            id = sensorId,
                            name = sensorName,
                            firmware = sensorFirmware,
                            batteryPercentAtStart = sensorBatteryAtStart,
                        ),
                    config =
                        ConfigJson(
                            hr = config.hr,
                            ecg = config.ecg,
                            acc = config.acc,
                            ecgRateHz = config.ecgRateHz,
                            accRateHz = config.accRateHz,
                            accRangeG = config.accRangeG,
                        ),
                    onDevice = onDevice,
                )
            )
            .jsonObject
    // Only an on-device recording carries that object: a streamed one has no such key at all.
    return if (onDevice == null) JsonObject(json - ON_DEVICE) else json
  }

  companion object {
    fun fromJson(o: JsonObject): RecordingMeta {
      val json = metaJson.decodeFromJsonElement<MetaJson>(o)
      return RecordingMeta(
          recordingId = json.sessionId,
          state = RecordingPhase.fromWire(json.state),
          mode = RecordingMode.fromWire(json.mode) ?: RecordingMode.STREAM,
          startUtcMs = json.startUtcMs,
          endUtcMs = json.endUtcMs,
          timezone = json.timezone,
          appVersion = json.app.version,
          appVersionCode = json.app.versionCode,
          phoneManufacturer = json.phone.manufacturer,
          phoneModel = json.phone.model,
          android = json.phone.android,
          sdk = json.phone.sdk,
          sensorKind = json.sensor.kind,
          sensorId = json.sensor.id,
          sensorName = json.sensor.name,
          sensorFirmware = json.sensor.firmware,
          sensorBatteryAtStart = json.sensor.batteryPercentAtStart,
          config =
              RecordingConfig(
                  hr = json.config.hr,
                  ecg = json.config.ecg,
                  acc = json.config.acc,
                  ecgRateHz = json.config.ecgRateHz,
                  accRateHz = json.config.accRateHz,
                  accRangeG = json.config.accRangeG,
              ),
          fetchPending = json.onDevice?.fetchPending ?: false,
          importedEntries = json.onDevice?.importedEntries.orEmpty(),
          importStarted = json.onDevice?.importStarted?.takeIf { it.isNotEmpty() },
          // A key that is not a stream id is skipped rather than making the file unreadable.
          importStart =
              json.onDevice?.importStart.orEmpty().mapNotNull { (key, position) ->
                key.toIntOrNull()?.let {
                  it to LogPosition(bytes = position.bytes, frames = position.frames)
                }
              }.toMap(),
          trackerRemovalPending =
              json.onDevice?.trackerRemovalPending.orEmpty().filter { it.isNotEmpty() },
          trackerRemovalFailures = json.onDevice?.trackerRemovalFailures ?: 0,
      )
    }

    const val FILE_NAME = "meta.json"

    /** The object only an on-device recording has. */
    private const val ON_DEVICE = "on_device"

    /**
     * The file itself. [encodeDefaults] and [explicitNulls] write every key the format has; a value
     * this version cannot name falls back to its default instead of making the file unreadable, and
     * a key a newer version added is ignored.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private val metaJson = Json {
      encodeDefaults = true
      explicitNulls = true
      ignoreUnknownKeys = true
      coerceInputValues = true
      prettyPrint = true
      prettyPrintIndent = "  "
    }

    fun read(dir: File): RecordingMeta? =
        try {
          fromJson(metaJson.parseToJsonElement(File(dir, FILE_NAME).readText()).jsonObject)
        } catch (_: Exception) {
          null
        }
  }

  /**
   * Replaces meta.json atomically: write a temp file, fsync it, rename over the old one. Returns
   * false when the phone refused the write, so a caller at the end of a recording can record the
   * failure instead of crashing on it.
   */
  fun write(dir: File): Boolean =
      try {
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use {
          it.write(metaJson.encodeToString(JsonElement.serializer(), toJson()).toByteArray())
          it.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            File(dir, FILE_NAME).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
      } catch (_: IOException) {
        false
      }
}

/** Append-only events.jsonl. One `write()` per line, shared by the writer and the watchdog.
 * Events are rare, so each one is fsynced. */
object EventLog {
  const val FILE_NAME = "events.jsonl"

  fun append(dir: File, phoneNs: Long, elapsedNs: Long, type: String, message: String) {
    val line =
        Json.encodeToString(
            EventJson(phoneNs = phoneNs, elapsedNs = elapsedNs, type = type, message = message)
        ) + "\n"
    FileOutputStream(File(dir, FILE_NAME), true).use {
      it.write(line.toByteArray())
      it.fd.sync()
    }
  }
}

/** meta.json as it is on disk: the keys and the value of each. */
@Serializable
private data class MetaJson(
    val format: String = "taiga-recording",
    val version: Int = 1,
    @SerialName("session_id") val sessionId: String,
    val state: String = "",
    val mode: String = "",
    @SerialName("start_utc_ms") val startUtcMs: Long,
    @SerialName("end_utc_ms") val endUtcMs: Long? = null,
    val timezone: String = "",
    val app: AppJson,
    val phone: PhoneJson,
    val sensor: SensorJson,
    val config: ConfigJson,
    @SerialName("on_device") val onDevice: OnDeviceJson? = null,
)

@Serializable
private data class AppJson(
    val version: String = "",
    @SerialName("version_code") val versionCode: Int = 0,
)

@Serializable
private data class PhoneJson(
    val manufacturer: String = "",
    val model: String = "",
    val android: String = "",
    val sdk: Int = 0,
)

@Serializable
private data class SensorJson(
    val kind: String,
    val id: String,
    val name: String = "",
    val firmware: String? = null,
    @SerialName("battery_percent_at_start") val batteryPercentAtStart: Int? = null,
)

@Serializable
private data class ConfigJson(
    val hr: Boolean = true,
    val ecg: Boolean = false,
    val acc: Boolean = false,
    @SerialName("ecg_rate_hz") val ecgRateHz: Int = 130,
    @SerialName("acc_rate_hz") val accRateHz: Int = 50,
    @SerialName("acc_range_g") val accRangeG: Int = 8,
)

/** The tracker's part of an on-device recording: what is imported and what still has to go. */
@Serializable
private data class OnDeviceJson(
    @SerialName("fetch_pending") val fetchPending: Boolean = false,
    @SerialName("imported_entries") val importedEntries: Map<String, Int> = emptyMap(),
    @SerialName("import_started") val importStarted: String? = null,
    @SerialName("import_start") val importStart: Map<String, PositionJson> = emptyMap(),
    @SerialName("tracker_removal_pending") val trackerRemovalPending: List<String> = emptyList(),
    @SerialName("tracker_removal_failures") val trackerRemovalFailures: Int = 0,
)

/** Where one stream log stood before an import began. */
@Serializable
private data class PositionJson(val bytes: Long, val frames: Long)

/** One line of events.jsonl. */
@Serializable
private data class EventJson(
    @SerialName("phone_ns") val phoneNs: Long,
    @SerialName("elapsed_ns") val elapsedNs: Long,
    val type: String,
    val message: String,
)
