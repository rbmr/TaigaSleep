package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.ageAt
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SleepECG is the only algorithm that takes the user's personal data as model features. */
internal const val SLEEP_ECG = "sleep-ecg"

/**
 * The SleepECG metadata parameters for a recording that started on [startDate], with only the
 * fields the user filled in. The date of birth itself never leaves the phone: the model gets the
 * age on the recording's date.
 */
internal fun sleepEcgParams(data: PersonalData, startDate: LocalDate?): JsonObject =
    buildJsonObject {
      if (startDate != null) {
        data.dateOfBirth?.let { dateOfBirth -> ageAt(dateOfBirth, startDate) }?.let {
          put("age", it)
        }
      }
      data.sex?.let { put("gender", it.wire) }
      data.weightKg?.let { put("weight", it) }
    }

/**
 * The parameters of one analysis request. Only SleepECG accepts them; wav2sleep and baseline reject
 * an unknown parameter. No parameters at all keeps the request exactly as it was before.
 */
internal fun analysisParams(
    algorithm: String,
    data: PersonalData,
    startDate: LocalDate?,
): JsonObject? =
    if (algorithm == SLEEP_ECG) sleepEcgParams(data, startDate).takeIf { it.isNotEmpty() } else null
