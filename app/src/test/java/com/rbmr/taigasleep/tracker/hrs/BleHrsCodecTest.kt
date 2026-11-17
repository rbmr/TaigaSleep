package com.rbmr.taigasleep.tracker.hrs

import com.rbmr.taigasleep.tracker.BleHrsCodec
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Heart Rate Measurement decoder, on every flag combination, read from the same fixture the
 * worker test reads. The fixture is checked in, so a change to either decoder has to keep both.
 */
class BleHrsCodecTest {
  private fun fixture(): File {
    var dir = File(System.getProperty("user.dir"))
    while (dir.parentFile != null) {
      val candidate = File(dir, "shared/codec-fixtures/heart-rate-measurement.json")
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    error("shared/codec-fixtures/heart-rate-measurement.json not found")
  }

  @Test
  fun `every flag combination decodes the same way in the app`() {
    val root = Json.parseToJsonElement(fixture().readText()).jsonObject
    val cases = root["cases"]!!.jsonArray
    assertEquals("every combination of the five defined flags", 32, cases.size)
    for (case in cases) {
      val item = case.jsonObject
      val name = item["name"]!!.jsonPrimitive.content
      val value = item["value_hex"]!!.jsonPrimitive.content.hexBytes()
      val expected = item["expected"]!!.jsonObject
      val decoded = BleHrsCodec.decodeMeasurement(value)
      assertEquals(name, expected["hr_bpm"]!!.jsonPrimitive.int, decoded.hr)
      assertEquals(
          name,
          expected["contact_supported"]!!.jsonPrimitive.boolean,
          decoded.contactSupported,
      )
      assertEquals(name, expected["contact"]!!.jsonPrimitive.boolean, decoded.contact)
      assertEquals(name, expected["rr_present"]!!.jsonPrimitive.boolean, decoded.rrPresent)
      assertEquals(
          name,
          expected["rr_ms"]!!.jsonArray.map { it.jsonPrimitive.int },
          decoded.rrMs.toList(),
      )
      assertEquals(
          name,
          expected["energy_expended"]!!.jsonPrimitive.intOrNull,
          decoded.energyExpended,
      )
    }
  }

  @Test
  fun `the stored frame keeps the characteristic bytes verbatim`() {
    val value = byteArrayOf(0x16, 60, 0x00, 0x02)
    val encoded = BleHrsCodec.encode(1_000, 2_000, value)
    val decoded = BleHrsCodec.decode(encoded.bytes)
    assertEquals(60, decoded.hr)
    assertTrue(decoded.rrPresent)
    assertEquals(listOf(500), decoded.rrMs.toList())
  }

  private fun String.hexBytes(): ByteArray =
      chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
