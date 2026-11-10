package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.Sex
import java.time.LocalDate
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisParamsTest {
  private val startDate = LocalDate.of(2026, 6, 15)
  private val full =
      PersonalData(
          dateOfBirth = LocalDate.of(1990, 6, 15),
          sex = Sex.FEMALE,
          weightKg = 72.5,
      )

  @Test
  fun `sleep-ecg gets the age on the recording date, the sex and the weight`() {
    val params = sleepEcgParams(full, startDate)

    assertEquals(36, params["age"]?.jsonPrimitive?.int)
    assertEquals("female", params["gender"]?.jsonPrimitive?.content)
    assertEquals(72.5, params["weight"]?.jsonPrimitive?.double ?: 0.0, 0.0)
  }

  @Test
  fun `only the fields that are filled in are sent`() {
    assertEquals(setOf("gender"), sleepEcgParams(PersonalData(sex = Sex.MALE), startDate).keys)
    assertEquals(setOf("weight"), sleepEcgParams(PersonalData(weightKg = 80.0), startDate).keys)
    assertEquals(
        setOf("age"),
        sleepEcgParams(PersonalData(dateOfBirth = LocalDate.of(1990, 6, 15)), startDate).keys,
    )
  }

  @Test
  fun `a recording without a known start date sends no age`() {
    val params = sleepEcgParams(full, startDate = null)

    assertTrue("age" !in params)
    assertEquals("female", params["gender"]?.jsonPrimitive?.content)
  }

  @Test
  fun `an empty profile asks for no parameters at all`() {
    assertTrue(sleepEcgParams(PersonalData(), startDate).isEmpty())
    assertNull(analysisParams(SLEEP_ECG, PersonalData(), startDate))
  }

  @Test
  fun `only sleep-ecg receives the personal data`() {
    assertEquals(sleepEcgParams(full, startDate), analysisParams("sleep-ecg", full, startDate))
    assertNull(analysisParams("wav2sleep", full, startDate))
    assertNull(analysisParams("baseline", full, startDate))
  }

  @Test
  fun `a changed value produces different parameters`() {
    val male = sleepEcgParams(full.copy(sex = Sex.MALE), startDate)
    val female = sleepEcgParams(full, startDate)
    val heavier = sleepEcgParams(full.copy(weightKg = 80.0), startDate)

    assertTrue(male != female)
    assertTrue(heavier != female)
  }
}
