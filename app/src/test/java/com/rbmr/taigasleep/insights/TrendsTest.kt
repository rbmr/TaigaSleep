package com.rbmr.taigasleep.insights

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsTest {
  private val zone = ZoneId.of("Europe/Berlin")
  private val today = LocalDate.of(2026, 11, 10)

  private fun recording(
      key: String,
      start: String,
      end: String,
      items: Map<String, Double> = emptyMap(),
  ): TrendRecording {
    val from = java.time.LocalDateTime.parse(start).atZone(zone).toInstant().toEpochMilli()
    val to = java.time.LocalDateTime.parse(end).atZone(zone).toInstant().toEpochMilli()
    return TrendRecording(
        key = key,
        localId = key,
        remoteId = null,
        analysisId = "analysis-$key",
        startUtcMs = from,
        endUtcMs = to,
        items = items,
    )
  }

  private fun goodNight(key: String, wakeDate: String, totalSleep: Double) =
      recording(
          key,
          "$wakeDate".let { date ->
            LocalDate.parse(date).minusDays(1).toString() + "T23:00"
          },
          "${wakeDate}T07:00",
          mapOf(
              TrendItems.TOTAL_SLEEP to totalSleep,
              TrendItems.DATA_QUALITY to 90.0,
          ),
      )

  private fun trends(recordings: List<TrendRecording>, range: Int = 7) =
      trendsFor(recordings, range, today, zone)

  @Test
  fun `a night belongs to the local date it ended and keeps the day order`() {
    val result =
        trends(
            listOf(
                recording("late", "2026-11-09T23:30", "2026-11-10T07:00"),
                recording("early", "2026-11-06T22:00", "2026-11-07T06:00"),
            )
        )

    assertEquals(
        listOf(
            LocalDate.of(2026, 11, 4),
            LocalDate.of(2026, 11, 5),
            LocalDate.of(2026, 11, 6),
            LocalDate.of(2026, 11, 7),
            LocalDate.of(2026, 11, 8),
            LocalDate.of(2026, 11, 9),
            LocalDate.of(2026, 11, 10),
        ),
        result.days,
    )
    assertEquals(7, result.nights.size)
    assertEquals("early", result.nights[3]?.key)
    assertEquals("late", result.nights[6]?.key)
    assertNull(result.nights[4])
  }

  @Test
  fun `two recordings ending on one date keep the longest`() {
    val result =
        trends(
            listOf(
                recording("short", "2026-11-07T23:00", "2026-11-08T05:00"),
                recording("long", "2026-11-07T21:30", "2026-11-08T07:00"),
            )
        )

    val night = result.nights.single { it != null }
    assertEquals("long", night?.key)
    assertEquals(21 * 60 + 30, night?.startMinuteOfDay)
  }

  @Test
  fun `a recording that ends after midnight belongs to the next date`() {
    val result = trends(listOf(recording("midnight", "2026-11-09T22:00", "2026-11-10T00:30")))

    assertEquals("midnight", result.nights.last()?.key)
    assertEquals(LocalDate.of(2026, 11, 10), result.nights.last()?.date)
  }

  @Test
  fun `averages use only nights with a value, three hours and usable data`() {
    val recordings =
        listOf(
            goodNight("good-1", "2026-11-06", 8.0 * 3600),
            goodNight("good-2", "2026-11-07", 9.0 * 3600),
            // Two hours of recording: too short, however good the value.
            recording(
                "short",
                "2026-11-04T23:00",
                "2026-11-05T01:00",
                mapOf(
                    TrendItems.TOTAL_SLEEP to 3000.0,
                    TrendItems.DATA_QUALITY to 90.0,
                ),
            ),
            // Long enough but poor data.
            recording(
                "poor",
                "2026-11-07T23:00",
                "2026-11-08T08:00",
                mapOf(
                    TrendItems.TOTAL_SLEEP to 4.0 * 3600,
                    TrendItems.DATA_QUALITY to 40.0,
                ),
            ),
            // Long enough and good, but without the item.
            recording(
                "missing",
                "2026-11-08T23:00",
                "2026-11-09T08:00",
                mapOf(TrendItems.DATA_QUALITY to 90.0),
            ),
            // Long enough but without a data quality.
            recording(
                "unknown-quality",
                "2026-11-09T22:00",
                "2026-11-10T08:00",
                mapOf(TrendItems.TOTAL_SLEEP to 6.0 * 3600),
            ),
        )

    val average = trends(recordings).totalSleep

    assertEquals(8.5 * 3600, average.value ?: 0.0, 0.001)
    assertEquals(2, average.nights)
    assertTrue(average.enough)
  }

  @Test
  fun `the circular mean crosses midnight`() {
    val result =
        trends(
            listOf(
                recording(
                    "before-midnight",
                    "2026-11-06T23:30",
                    "2026-11-07T07:30",
                    mapOf(TrendItems.DATA_QUALITY to 90.0),
                ),
                recording(
                    "after-midnight",
                    "2026-11-07T00:30",
                    "2026-11-08T07:30",
                    mapOf(TrendItems.DATA_QUALITY to 90.0),
                ),
            )
        )

    val bedtime = result.schedule.bedtime
    assertEquals(0, bedtime?.meanMinuteOfDay)
    assertEquals(30, bedtime?.spreadMinutes)
    assertEquals(7 * 60 + 30, result.schedule.wake?.meanMinuteOfDay)
    assertEquals(0, result.schedule.wake?.spreadMinutes)
    assertEquals(2, result.schedule.nights)
  }

  @Test
  fun `sleep onset and final awakening come from the result items`() {
    val result =
        trends(
            listOf(
                recording(
                    "analyzed",
                    "2026-11-07T23:00",
                    "2026-11-08T07:00",
                    mapOf(
                        TrendItems.SLEEP_LATENCY to 15.0 * 60,
                        TrendItems.FINAL_AWAKENING to 10.0 * 60,
                        TrendItems.DATA_QUALITY to 90.0,
                    ),
                )
            )
        )

    val night = result.nights.single { it != null }!!
    assertEquals(23 * 60, night.startMinuteOfDay)
    assertEquals(7 * 60, night.endMinuteOfDay)
    assertEquals(23 * 60 + 15, night.onsetMinuteOfDay)
    assertEquals(6 * 60 + 50, night.finalAwakeningMinuteOfDay)
  }

  @Test
  fun `a night without a result has only the recording schedule`() {
    val result = trends(listOf(recording("plain", "2026-11-07T23:10", "2026-11-08T07:05")))

    val night = result.nights.single { it != null }!!
    assertNull(night.onsetMinuteOfDay)
    assertNull(night.finalAwakeningMinuteOfDay)
    assertNull(night.totalSleepSeconds)
    assertFalse(night.poorData)
  }

  @Test
  fun `a poor-data night is kept and flagged instead of dropped`() {
    val result =
        trends(
            listOf(
                recording(
                    "poor",
                    "2026-11-07T23:00",
                    "2026-11-08T07:00",
                    mapOf(
                        TrendItems.DATA_QUALITY to 40.0,
                        TrendItems.TOTAL_SLEEP to 6.0 * 3600,
                    ),
                ),
                recording(
                    "good",
                    "2026-11-08T23:00",
                    "2026-11-09T07:00",
                    mapOf(
                        TrendItems.DATA_QUALITY to 70.0,
                        TrendItems.TOTAL_SLEEP to 7.0 * 3600,
                    ),
                ),
            )
        )

    val poor = result.nights.first { it?.key == "poor" }!!
    val good = result.nights.first { it?.key == "good" }!!

    assertTrue(poor.poorData)
    assertEquals(6.0 * 3600, poor.totalSleepSeconds ?: 0.0, 0.001)
    assertFalse(good.poorData)
    // The poor night is not part of the average.
    assertEquals(7.0 * 3600, result.totalSleep.value ?: 0.0, 0.001)
    assertEquals(1, result.totalSleep.nights)
  }

  @Test
  fun `the intended sleep duration crosses midnight`() {
    fun intended(bedtime: LocalTime?, wake: LocalTime?) =
        trendsFor(emptyList(), 7, today, zone, bedtime, wake).intendedSleepMinutes

    assertEquals(8 * 60, intended(LocalTime.of(23, 0), LocalTime.of(7, 0)))
    assertEquals(7 * 60, intended(LocalTime.of(1, 0), LocalTime.of(8, 0)))
    assertNull(intended(LocalTime.of(23, 0), null))
    assertNull(intended(null, LocalTime.of(7, 0)))
  }

  @Test
  fun `one average night is too few to show a trend`() {
    val result = trends(listOf(goodNight("only", "2026-11-07", 8.0 * 3600)))

    assertEquals(1, result.totalSleep.nights)
    assertFalse(result.totalSleep.enough)
    assertFalse(result.schedule.enough)
  }

  @Test
  fun `two average nights are enough`() {
    val result =
        trends(
            listOf(
                goodNight("one", "2026-11-07", 8.0 * 3600),
                goodNight("two", "2026-11-08", 7.0 * 3600),
            )
        )

    assertTrue(result.totalSleep.enough)
    assertTrue(result.schedule.enough)
  }

  @Test
  fun `a range with no recording has empty days and no averages`() {
    val result = trends(emptyList())

    assertEquals(7, result.nights.count { it == null })
    assertNull(result.totalSleep.value)
    assertFalse(result.totalSleep.enough)
    assertNull(result.schedule.bedtime)
  }

  @Test
  fun `the night axis puts evening above midnight and morning below it`() {
    assertEquals(540, nightMinuteOfDay(21 * 60))
    assertEquals(690, nightMinuteOfDay(23 * 60 + 30))
    assertEquals(750, nightMinuteOfDay(30))
    assertEquals(1260, nightMinuteOfDay(9 * 60))
    assertEquals(21 * 60, clockMinuteOfNightMinute(540))
    assertEquals(30, clockMinuteOfNightMinute(750))
  }
}
