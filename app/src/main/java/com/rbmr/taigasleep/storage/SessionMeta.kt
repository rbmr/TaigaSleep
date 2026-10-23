package com.rbmr.taigasleep.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

enum class SessionState(val wire: String) {
  RECORDING("recording"),
  STOPPED("stopped"),
  FORCED_STOP("forced_stop"),
  INTERRUPTED("interrupted");

  companion object {
    fun fromWire(value: String?): SessionState =
        entries.firstOrNull { it.wire == value } ?: INTERRUPTED
  }
}

data class SessionConfig(
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

data class SessionMeta(
    val sessionId: String,
    val state: SessionState,
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
    val config: SessionConfig,
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("format", "taiga-recording")
          .put("version", 1)
          .put("session_id", sessionId)
          .put("state", state.wire)
          .put("start_utc_ms", startUtcMs)
          .put("end_utc_ms", endUtcMs ?: JSONObject.NULL)
          .put("timezone", timezone)
          .put(
              "app",
              JSONObject().put("version", appVersion).put("version_code", appVersionCode),
          )
          .put(
              "phone",
              JSONObject()
                  .put("manufacturer", phoneManufacturer)
                  .put("model", phoneModel)
                  .put("android", android)
                  .put("sdk", sdk),
          )
          .put(
              "sensor",
              JSONObject()
                  .put("kind", sensorKind)
                  .put("id", sensorId)
                  .put("name", sensorName)
                  .put("firmware", sensorFirmware ?: JSONObject.NULL)
                  .put("battery_percent_at_start", sensorBatteryAtStart ?: JSONObject.NULL),
          )
          .put(
              "config",
              JSONObject()
                  .put("hr", config.hr)
                  .put("ecg", config.ecg)
                  .put("acc", config.acc)
                  .put("ecg_rate_hz", config.ecgRateHz)
                  .put("acc_rate_hz", config.accRateHz)
                  .put("acc_range_g", config.accRangeG),
          )

  companion object {
    fun fromJson(o: JSONObject): SessionMeta {
      val app = o.getJSONObject("app")
      val phone = o.getJSONObject("phone")
      val sensor = o.getJSONObject("sensor")
      val config = o.getJSONObject("config")
      return SessionMeta(
          sessionId = o.getString("session_id"),
          state = SessionState.fromWire(o.optString("state")),
          startUtcMs = o.getLong("start_utc_ms"),
          endUtcMs = if (o.isNull("end_utc_ms")) null else o.getLong("end_utc_ms"),
          timezone = o.optString("timezone"),
          appVersion = app.optString("version"),
          appVersionCode = app.optInt("version_code"),
          phoneManufacturer = phone.optString("manufacturer"),
          phoneModel = phone.optString("model"),
          android = phone.optString("android"),
          sdk = phone.optInt("sdk"),
          sensorKind = sensor.getString("kind"),
          sensorId = sensor.getString("id"),
          sensorName = sensor.optString("name"),
          sensorFirmware = if (sensor.isNull("firmware")) null else sensor.getString("firmware"),
          sensorBatteryAtStart =
              if (sensor.isNull("battery_percent_at_start")) null
              else sensor.getInt("battery_percent_at_start"),
          config =
              SessionConfig(
                  hr = config.optBoolean("hr", true),
                  ecg = config.optBoolean("ecg"),
                  acc = config.optBoolean("acc"),
                  ecgRateHz = config.optInt("ecg_rate_hz", 130),
                  accRateHz = config.optInt("acc_rate_hz", 50),
                  accRangeG = config.optInt("acc_range_g", 8),
              ),
      )
    }

    const val FILE_NAME = "meta.json"

    fun read(dir: File): SessionMeta? =
        try {
          fromJson(JSONObject(File(dir, FILE_NAME).readText()))
        } catch (_: Exception) {
          null
        }
  }

  /** Replaces meta.json atomically: write a temp file, fsync it, rename over the old one. */
  fun write(dir: File) {
    val tmp = File(dir, "$FILE_NAME.tmp")
    FileOutputStream(tmp).use {
      it.write(toJson().toString(2).toByteArray())
      it.fd.sync()
    }
    Files.move(
        tmp.toPath(),
        File(dir, FILE_NAME).toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
  }
}

/** Append-only events.jsonl. One `write()` per line, shared by the writer and the watchdog.
 * Events are rare, so each one is fsynced. */
object EventLog {
  const val FILE_NAME = "events.jsonl"

  fun append(dir: File, phoneNs: Long, elapsedNs: Long, type: String, message: String) {
    val line =
        JSONObject()
            .put("phone_ns", phoneNs)
            .put("elapsed_ns", elapsedNs)
            .put("type", type)
            .put("message", message)
            .toString() + "\n"
    FileOutputStream(File(dir, FILE_NAME), true).use {
      it.write(line.toByteArray())
      it.fd.sync()
    }
  }
}
