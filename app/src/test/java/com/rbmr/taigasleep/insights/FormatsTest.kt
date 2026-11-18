package com.rbmr.taigasleep.insights

import com.rbmr.taigasleep.testFormats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one formatter: the time format choice with the phone behind System, the units choice with
 * the locale behind System, and the words a length and a weight are written with.
 */
class FormatsTest {
  private val night = Instant.parse("2026-11-09T23:12:00Z").toEpochMilli()
  private val morning = Instant.parse("2026-11-10T07:05:00Z").toEpochMilli()

  /** A wall-clock time in [zone], as a recording's start is stored. */
  private fun at(zone: ZoneId, month: Int, day: Int, hour: Int, minute: Int): Long =
      LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

  @Test
  fun `twelve hour writes an am or pm time`() {
    val formats = testFormats(timeFormat = TimeFormat.TWELVE_HOUR)

    assertEquals("11:12 PM", formats.clockTime(night))
    assertEquals("7:05 AM", formats.clockTime(morning))
  }

  @Test
  fun `twenty four hour writes a padded clock`() {
    val formats = testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR)

    assertEquals("23:12", formats.clockTime(night))
    assertEquals("07:05", formats.clockTime(morning))
  }

  @Test
  fun `system follows a phone that is set to a twenty four hour clock`() {
    val formats = testFormats(timeFormat = TimeFormat.SYSTEM, phoneIs24Hour = true)

    assertTrue(formats.is24Hour)
    assertEquals("23:12", formats.clockTime(night))
  }

  @Test
  fun `system follows a phone that is set to a twelve hour clock`() {
    val formats = testFormats(timeFormat = TimeFormat.SYSTEM, phoneIs24Hour = false)

    assertFalse(formats.is24Hour)
    assertEquals("11:12 PM", formats.clockTime(night))
  }

  @Test
  fun `system without a phone follows the locale`() {
    assertFalse(testFormats(locale = Locale.US).is24Hour)
    assertTrue(testFormats(locale = Locale.UK).is24Hour)
  }

  @Test
  fun `a chosen clock wins over a phone set the other way`() {
    val twelve = testFormats(timeFormat = TimeFormat.TWELVE_HOUR, phoneIs24Hour = true)
    val twentyFour =
        testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR, phoneIs24Hour = false)

    assertEquals("11:12 PM", twelve.clockTime(night))
    assertEquals("23:12", twentyFour.clockTime(night))
  }

  @Test
  fun `a locale that writes the day first still writes the chosen clock`() {
    val german = Locale.GERMANY

    assertEquals("23:12", testFormats(locale = german).clockTime(night))
    assertEquals(
        "11:12 PM",
        testFormats(timeFormat = TimeFormat.TWELVE_HOUR, locale = german).clockTime(night),
    )
  }

  @Test
  fun `a time of day, a minute of the night and a date use the same clock`() {
    val formats = testFormats(timeFormat = TimeFormat.TWENTY_FOUR_HOUR)

    assertEquals("23:12", formats.clockTime(23, 12))
    assertEquals("23:12", formats.minuteOfDay(23 * 60 + 12))
    assertEquals("00:30", formats.minuteOfDay(24 * 60 + 30))
    assertEquals("Sat 7 Nov, 23:12", formats.dateAndTime(Instant.parse("2026-11-07T23:12:00Z").toEpochMilli()))
    assertEquals("-", formats.clockTime(0))
    assertEquals("-", formats.dateAndTime(0))
  }

  @Test
  fun `a recording is dated by the night it belongs to`() {
    val zone = ZoneId.of("Europe/Amsterdam")
    val formats = testFormats(locale = Locale.US, zone = zone)

    assertEquals("Sat 7 Nov", formats.recordingDate(at(zone, 11, 7, 23, 50)))
    assertEquals("Fri 13 Nov", formats.recordingDate(at(zone, 11, 14, 0, 33)))
    assertEquals("Fri 6 Nov", formats.recordingDate(at(zone, 11, 7, 2, 5)))
    assertEquals("-", formats.recordingDate(0))
  }

  @Test
  fun `a day of a trend has a long form and one for an axis`() {
    val day = LocalDate.of(2026, 11, 7)

    assertEquals("Sat 7 Nov", testFormats(locale = Locale.US).dayDate(day))
    assertEquals("7 Nov", testFormats(locale = Locale.US).shortDate(day))
    assertEquals("Sa. 7 Nov.", testFormats(locale = Locale.GERMANY).dayDate(day))
  }

  @Test
  fun `units follow the locale until one is chosen`() {
    assertEquals(MeasurementSystem.IMPERIAL, testFormats(locale = Locale.US).measurementSystem)
    assertEquals(MeasurementSystem.METRIC, testFormats(locale = Locale.UK).measurementSystem)
    assertEquals(MeasurementSystem.METRIC, testFormats(locale = Locale.GERMANY).measurementSystem)
    assertEquals(
        MeasurementSystem.METRIC,
        testFormats(units = Units.METRIC, locale = Locale.US).measurementSystem,
    )
    assertEquals(
        MeasurementSystem.IMPERIAL,
        testFormats(units = Units.IMPERIAL, locale = Locale.GERMANY).measurementSystem,
    )
  }

  @Test
  fun `a weight is shown in the unit the app uses`() {
    val metric = testFormats(units = Units.METRIC)
    val imperial = testFormats(units = Units.IMPERIAL)

    assertEquals("70 kg", metric.weight(70.0))
    assertEquals("154.3 lb", imperial.weight(70.0))
    assertEquals("154.3", imperial.weightNumber(70.0))
    assertEquals("Kilograms", metric.weightFieldLabel)
    assertEquals("Pounds", imperial.weightFieldLabel)
  }

  @Test
  fun `a typed weight is stored in kilograms`() {
    assertEquals(70.0, testFormats(units = Units.METRIC).parseWeight("70")!!, 1e-9)
    assertEquals(
        70.0,
        testFormats(units = Units.METRIC, locale = Locale.GERMANY).parseWeight("70,0")!!,
        1e-9,
    )
    assertEquals(69.989, testFormats(units = Units.IMPERIAL).parseWeight("154.3")!!, 0.001)
  }

  @Test
  fun `a weight does not drift when it is shown again after an edit`() {
    val metric = testFormats(units = Units.METRIC)
    val imperial = testFormats(units = Units.IMPERIAL)

    for (kg in listOf(70.0, 72.5, 90.0, 110.0, 1.0, 500.0)) {
      val typed = metric.weightNumber(kg)
      assertEquals(typed, metric.weightNumber(metric.parseWeight(typed)!!))
    }
    for (pounds in listOf("110", "154.3", "154.5", "220", "3", "1102")) {
      val kg = imperial.parseWeight(pounds)!!
      assertEquals(pounds, imperial.weightNumber(kg))
    }
  }

  @Test
  fun `a weight outside the stated range is refused`() {
    val imperial = testFormats(units = Units.IMPERIAL)

    assertEquals("3", imperial.weightBounds.start.toInt().toString())
    assertEquals("1102", imperial.weightBounds.endInclusive.toInt().toString())
    assertNull(imperial.parseWeight("2"))
    assertNull(imperial.parseWeight("1103"))
    assertNull(imperial.parseWeight("heavy"))
    assertNull(testFormats(units = Units.METRIC).parseWeight("0"))
    assertNull(testFormats(units = Units.METRIC).parseWeight("501"))
  }

  @Test
  fun aDurationBelowOneMinuteIsShownInSeconds() {
    val formats = testFormats()

    assertEquals("1s", formats.duration(1_000))
    assertEquals("56s", formats.duration(56_000))
    assertEquals("59s", formats.duration(59_000))
    assertEquals("0s", formats.duration(0))
    assertEquals("0s", formats.duration(-1))
  }

  @Test
  fun aDurationOfAMinuteOrMoreIsShownInMinutesAndHours() {
    val formats = testFormats()

    assertEquals("1m", formats.duration(60_000))
    assertEquals("1m", formats.duration(61_000))
    assertEquals("1h", formats.duration(3_600_000))
    assertEquals("1h 5m", formats.duration(3_900_000))
    assertEquals("7h 26m", formats.duration(26_760_000))
    assertEquals("8h 30m", formats.duration(30_600_000))
    assertEquals("45m", formats.duration(2_700_000))
  }

  @Test
  fun `a result number is written with its unit`() {
    val formats = testFormats()

    assertEquals("1h 30m", formats.itemValue(MetricValueKind.DURATION, 5400.0))
    assertEquals("86%", formats.itemValue(MetricValueKind.PERCENT, 85.6))
    assertEquals("52 bpm", formats.itemValue(MetricValueKind.BPM, 52.4))
    assertEquals("38 ms", formats.itemValue(MetricValueKind.MILLISECONDS, 37.6))
    assertEquals("12", formats.itemValue(MetricValueKind.COUNT, 12.0))
    assertEquals("0.04 g", formats.itemValue(MetricValueKind.GRAMS, 0.043))
    assertNull(formats.itemValue(MetricValueKind.BPM, null))
  }

  @Test
  fun `a chart axis label carries an amount and its unit`() {
    val formats = testFormats()

    assertEquals("86%", formats.percent("86"))
    assertEquals("58 bpm", formats.beatsPerMinute("58"))
    assertEquals("38 ms", formats.milliseconds("38"))
    assertEquals("7 h", formats.hours("7"))
  }
}
