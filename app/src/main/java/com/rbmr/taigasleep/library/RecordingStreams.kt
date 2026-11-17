@file:Suppress("ReturnCount", "CyclomaticComplexMethod")

package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.recording.store.StreamId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The streams a recording holds, as [StreamId] values, read from what the server stores: the
 * `streams` list of its meta, and when that is missing its old `config` or stored file names.
 */
fun RecordingDto.streams(): List<Int> {
  val meta = meta
  val declared = (meta?.get("streams") as? JsonArray)?.mapNotNull { element ->
    val item = element as? JsonObject ?: return@mapNotNull null
    val type = item["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
    val id =
        when (type) {
          "heart_rate",
          "rr_interval" -> StreamId.HR
          "ecg" -> StreamId.ECG
          "accelerometer" -> StreamId.ACC
          "ppg" -> StreamId.PPG
          "pulse_interval" -> StreamId.PPI
          "gyroscope" -> StreamId.GYRO
          "magnetometer" -> StreamId.MAG
          else -> null
        }
    id
  }
  if (!declared.isNullOrEmpty()) return declared.distinct()
  val config = meta?.get("config") as? JsonObject
  if (config != null) {
    val streams =
        buildList {
          if ((config["hr"] as? JsonPrimitive)?.booleanOrNull != false) add(StreamId.HR)
          if ((config["ecg"] as? JsonPrimitive)?.booleanOrNull == true) add(StreamId.ECG)
          if ((config["acc"] as? JsonPrimitive)?.booleanOrNull == true) add(StreamId.ACC)
        }
    if (streams.isNotEmpty()) return streams
  }
  val names = files?.keys.orEmpty()
  return listOf(
          "hr" to StreamId.HR,
          "ecg" to StreamId.ECG,
          "acc" to StreamId.ACC,
          "ppg" to StreamId.PPG,
          "ppi" to StreamId.PPI,
          "gyro" to StreamId.GYRO,
          "mag" to StreamId.MAG,
      )
      .filter { (prefix, _) -> names.any { it == "$prefix.log" || it.startsWith("$prefix.log.") } }
      .map { it.second }
}
