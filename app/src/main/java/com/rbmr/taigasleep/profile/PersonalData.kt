package com.rbmr.taigasleep.profile

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two values SleepECG's `gender` parameter accepts. */
enum class Sex(val wire: String) {
  MALE("male"),
  FEMALE("female"),
}

/**
 * What the user optionally enters about themselves. Everything is optional. The age derived for a
 * recording, the sex and the weight are sent with an analysis; the usual bedtime and wake time stay
 * on the phone and are only used to draw the History trends.
 */
data class PersonalData(
    val dateOfBirth: LocalDate? = null,
    val sex: Sex? = null,
    val weightKg: Double? = null,
    val usualBedtime: LocalTime? = null,
    val usualWakeTime: LocalTime? = null,
) {
  val isEmpty: Boolean
    get() =
        dateOfBirth == null &&
            sex == null &&
            weightKg == null &&
            usualBedtime == null &&
            usualWakeTime == null
}

/**
 * Completed years at [on], or null when [on] is before the birth date. A birth date of 29 February
 * has its birthday on 1 March in a year without that day, which is how `java.time` counts completed
 * years.
 */
fun ageAt(dateOfBirth: LocalDate, on: LocalDate): Int? =
    if (on.isBefore(dateOfBirth)) null else Period.between(dateOfBirth, on).years

/**
 * The date the recording started, in the timezone the recording was made in. A missing or unknown
 * timezone falls back to [fallback], which is the phone's zone.
 */
fun recordingStartDate(
    startUtcMs: Long,
    timezone: String?,
    fallback: ZoneId = ZoneId.systemDefault(),
): LocalDate {
  val zone =
      timezone?.takeIf { it.isNotBlank() }?.let { name ->
        runCatching { ZoneId.of(name) }.getOrNull()
      } ?: fallback
  return Instant.ofEpochMilli(startUtcMs).atZone(zone).toLocalDate()
}

/** The one owner of the personal data the user entered. Every value can be set and cleared. */
class PersonalDataStore internal constructor(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("personal_data", Context.MODE_PRIVATE)

  private val _data = MutableStateFlow(load())
  val data: StateFlow<PersonalData> = _data.asStateFlow()

  fun setDateOfBirth(value: LocalDate?) {
    prefs.edit {
      if (value == null) remove(KEY_DATE_OF_BIRTH) else putString(KEY_DATE_OF_BIRTH, value.toString())
    }
    _data.value = _data.value.copy(dateOfBirth = value)
  }

  fun setSex(value: Sex?) {
    prefs.edit {
      if (value == null) remove(KEY_SEX) else putString(KEY_SEX, value.wire)
    }
    _data.value = _data.value.copy(sex = value)
  }

  fun setWeightKg(value: Double?) {
    prefs.edit {
      if (value == null) remove(KEY_WEIGHT) else putString(KEY_WEIGHT, value.toString())
    }
    _data.value = _data.value.copy(weightKg = value)
  }

  fun setUsualBedtime(value: LocalTime?) {
    prefs.edit {
      if (value == null) remove(KEY_USUAL_BEDTIME) else putString(KEY_USUAL_BEDTIME, value.toString())
    }
    _data.value = _data.value.copy(usualBedtime = value)
  }

  fun setUsualWakeTime(value: LocalTime?) {
    prefs.edit {
      if (value == null) remove(KEY_USUAL_WAKE_TIME)
      else putString(KEY_USUAL_WAKE_TIME, value.toString())
    }
    _data.value = _data.value.copy(usualWakeTime = value)
  }

  private fun load(): PersonalData =
      PersonalData(
          dateOfBirth =
              prefs.getString(KEY_DATE_OF_BIRTH, null)?.let { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull()
              },
          sex =
              prefs.getString(KEY_SEX, null)?.let { raw ->
                Sex.entries.firstOrNull { it.wire == raw }
              },
          weightKg = prefs.getString(KEY_WEIGHT, null)?.toDoubleOrNull(),
          usualBedtime =
              prefs.getString(KEY_USUAL_BEDTIME, null)?.let { raw ->
                runCatching { LocalTime.parse(raw) }.getOrNull()
              },
          usualWakeTime =
              prefs.getString(KEY_USUAL_WAKE_TIME, null)?.let { raw ->
                runCatching { LocalTime.parse(raw) }.getOrNull()
              },
      )

  private companion object {
    const val KEY_DATE_OF_BIRTH = "date_of_birth"
    const val KEY_SEX = "sex"
    const val KEY_WEIGHT = "weight_kg"
    const val KEY_USUAL_BEDTIME = "usual_bedtime"
    const val KEY_USUAL_WAKE_TIME = "usual_wake_time"
  }
}
