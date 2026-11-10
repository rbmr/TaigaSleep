package com.rbmr.taigasleep.sync.db

import androidx.room.TypeConverter
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import kotlinx.serialization.encodeToString

/** The values the rows store as text and lists, so a table stays readable in a database dump. */
class RowConverters {
  @TypeConverter fun phaseTo(value: UploadPhase): String = value.name

  @TypeConverter fun phaseFrom(value: String): UploadPhase = UploadPhase.valueOf(value)

  @TypeConverter fun failureTo(value: UploadFailure?): String? = value?.name

  @TypeConverter fun failureFrom(value: String?): UploadFailure? =
      value?.let { UploadFailure.valueOf(it) }

  @TypeConverter fun idsTo(value: List<String>): String = rowJson.encodeToString(value)

  @TypeConverter fun idsFrom(value: String): List<String> =
      if (value.isBlank()) emptyList() else rowJson.decodeFromString(value)
}
