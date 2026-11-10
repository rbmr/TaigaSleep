package com.rbmr.taigasleep.recording.store

import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** meta.json is the recording's contract on disk: reading and writing it must not change it. */
class RecordingMetaTest {
  private val root = Files.createTempDirectory("taiga-meta").toFile()

  private fun fixture(): String =
      requireNotNull(javaClass.getResource("/recording/meta-on-device.json")) {
            "meta-on-device.json not found"
          }
          .readText()

  private fun dir(name: String): File = File(root, name).apply { mkdirs() }

  private fun meta(name: String): RecordingMeta =
      RecordingMeta(
          recordingId = "20261023_223000-k3f9",
          state = RecordingPhase.STOPPED,
          startUtcMs = 1792798200000,
          endUtcMs = null,
          timezone = "Europe/Amsterdam",
          appVersion = "0.7.40",
          appVersionCode = 7040,
          phoneManufacturer = "OnePlus",
          phoneModel = "CPH2769",
          android = "16",
          sdk = 36,
          sensorKind = "polar-h10",
          sensorId = "069A4737",
          sensorName = "Polar H10 069A4737",
          sensorFirmware = null,
          sensorBatteryAtStart = null,
          config = RecordingConfig(hr = true, ecg = false, acc = false),
      )

  @Test
  fun `an on device meta reads with every field and writes back the same keys and values`() {
    val dir = dir("on-device")
    File(dir, RecordingMeta.FILE_NAME).writeText(fixture())

    val meta = requireNotNull(RecordingMeta.read(dir)) { "the fixture did not read" }
    assertEquals("20261023_223000-k3f9", meta.recordingId)
    assertEquals(RecordingPhase.STOPPED, meta.state)
    assertEquals(RecordingMode.ON_DEVICE, meta.mode)
    assertEquals(1792798200000, meta.startUtcMs)
    assertEquals(1792805400000, meta.endUtcMs)
    assertEquals("Europe/Amsterdam", meta.timezone)
    assertEquals("0.7.40", meta.appVersion)
    assertEquals(7040, meta.appVersionCode)
    assertEquals("OnePlus", meta.phoneManufacturer)
    assertEquals("CPH2769", meta.phoneModel)
    assertEquals("16", meta.android)
    assertEquals(36, meta.sdk)
    assertEquals("polar-h10", meta.sensorKind)
    assertEquals("069A4737", meta.sensorId)
    assertEquals("Polar H10 069A4737", meta.sensorName)
    assertEquals("3.0.35", meta.sensorFirmware)
    assertEquals(87, meta.sensorBatteryAtStart)
    assertEquals(RecordingConfig(hr = true, ecg = true, acc = true), meta.config)
    assertTrue(meta.fetchPending)
    assertEquals(mapOf("exercise-a" to 1234, "exercise-b" to 7), meta.importedEntries)
    assertEquals("exercise-b", meta.importStarted)
    assertEquals(
        mapOf(1 to LogPosition(bytes = 8192, frames = 128), 3 to LogPosition(bytes = 4096, frames = 32)),
        meta.importStart,
    )
    assertEquals(listOf("exercise-a"), meta.trackerRemovalPending)
    assertEquals(2, meta.trackerRemovalFailures)

    assertTrue(meta.write(dir))

    val before = Json.parseToJsonElement(fixture()).jsonObject
    val after = Json.parseToJsonElement(File(dir, RecordingMeta.FILE_NAME).readText()).jsonObject
    assertEquals(before, after)
  }

  @Test
  fun `a streamed meta has no on device object and keeps its open end as null`() {
    val dir = dir("stream")
    assertTrue(meta("stream").write(dir))

    val json = Json.parseToJsonElement(File(dir, RecordingMeta.FILE_NAME).readText()).jsonObject
    assertFalse("on_device" in json)
    assertTrue("end_utc_ms" in json)
    assertEquals(JsonNull, json["end_utc_ms"])
    assertEquals("stream", json["mode"]?.jsonPrimitive?.content)
    assertEquals(JsonNull, json["sensor"]?.jsonObject?.get("firmware"))

    val read = requireNotNull(RecordingMeta.read(dir))
    assertEquals(RecordingMode.STREAM, read.mode)
    assertNull(read.endUtcMs)
    assertNull(read.sensorFirmware)
    assertNull(read.sensorBatteryAtStart)
    assertEquals(emptyMap<String, Int>(), read.importedEntries)
    assertEquals(emptyList<String>(), read.trackerRemovalPending)
    assertEquals(meta("stream"), read)
  }
}
