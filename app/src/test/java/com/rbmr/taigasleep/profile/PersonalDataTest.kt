package com.rbmr.taigasleep.profile

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalDataTest {
  @Test
  fun `the age is the completed years on the given date`() {
    val dateOfBirth = LocalDate.of(1990, 6, 15)

    assertEquals(35, ageAt(dateOfBirth, LocalDate.of(2026, 6, 14)))
    assertEquals(36, ageAt(dateOfBirth, LocalDate.of(2026, 6, 15)))
    assertEquals(36, ageAt(dateOfBirth, LocalDate.of(2026, 6, 16)))
  }

  @Test
  fun `a leap day birthday is counted from the first of march in other years`() {
    val dateOfBirth = LocalDate.of(2000, 2, 29)

    assertEquals(22, ageAt(dateOfBirth, LocalDate.of(2023, 2, 27)))
    assertEquals(22, ageAt(dateOfBirth, LocalDate.of(2023, 2, 28)))
    assertEquals(23, ageAt(dateOfBirth, LocalDate.of(2023, 3, 1)))
    assertEquals(24, ageAt(dateOfBirth, LocalDate.of(2024, 2, 29)))
  }

  @Test
  fun `a birth date after the recording date has no age`() {
    assertNull(ageAt(LocalDate.of(2000, 1, 1), LocalDate.of(1999, 12, 31)))
  }

  @Test
  fun `the start date is taken in the recording's timezone`() {
    val start = Instant.parse("2026-11-05T23:30:00Z").toEpochMilli()

    assertEquals(LocalDate.of(2026, 11, 6), recordingStartDate(start, "Europe/Berlin"))
    assertEquals(LocalDate.of(2026, 11, 5), recordingStartDate(start, "UTC"))
  }

  @Test
  fun `a missing or unknown timezone falls back to the phone's zone`() {
    val start = Instant.parse("2026-11-05T23:30:00Z").toEpochMilli()
    val fallback = ZoneId.of("Europe/Berlin")

    assertEquals(LocalDate.of(2026, 11, 6), recordingStartDate(start, "Not/AZone", fallback))
    assertEquals(LocalDate.of(2026, 11, 6), recordingStartDate(start, null, fallback))
    assertEquals(LocalDate.of(2026, 11, 6), recordingStartDate(start, "", fallback))
  }
}
