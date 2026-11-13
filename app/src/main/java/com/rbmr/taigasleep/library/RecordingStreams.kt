package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.recording.store.StreamId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The streams a recording holds, as [StreamId] values, read from what the server stores: the
 * `config` of its meta, and when that is missing the names of its stored files.
 */
fun RecordingDto.streams(): List<Int> {
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
  return listOf("hr" to StreamId.HR, "ecg" to StreamId.ECG, "acc" to StreamId.ACC)
      .filter { (prefix, _) -> names.any { it == "$prefix.log" || it.startsWith("$prefix.log.") } }
      .map { it.second }
}
