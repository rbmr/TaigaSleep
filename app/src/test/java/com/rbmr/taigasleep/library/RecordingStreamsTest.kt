package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.recording.store.StreamId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStreamsTest {
  private fun dto(meta: String? = null, files: String? = null) =
      RecordingDto(
          meta = meta?.let { Json.parseToJsonElement(it).jsonObject },
          files = files?.let { Json.parseToJsonElement(it).jsonObject },
      )

  @Test
  fun `the streams come from the config of the meta`() {
    val streams = dto(meta = """{"config":{"hr":true,"ecg":true,"acc":false}}""").streams()

    assertEquals(listOf(StreamId.HR, StreamId.ECG), streams)
  }

  @Test
  fun `without a config the stored file names tell the streams`() {
    val streams = dto(files = """{"hr.log":{},"acc.log.gz":{}}""").streams()

    assertEquals(listOf(StreamId.HR, StreamId.ACC), streams)
  }

  @Test
  fun `a record with neither has no streams`() {
    assertEquals(emptyList<Int>(), dto().streams())
  }
}
