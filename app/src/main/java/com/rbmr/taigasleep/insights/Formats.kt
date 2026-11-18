package com.rbmr.taigasleep.insights

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the user wants a time of day written. The app follows the phone until a choice is made. */
enum class TimeFormat {
  SYSTEM,
  TWELVE_HOUR,
  TWENTY_FOUR_HOUR,
}

/** How the user wants a measurement written. The app follows the phone's locale until then. */
enum class Units {
  SYSTEM,
  METRIC,
  IMPERIAL,
}

/** The measurement system the app actually shows, once [Units] and the locale are resolved. */
enum class MeasurementSystem {
  METRIC,
  IMPERIAL,
}

/**
 * The words the formatter puts next to a number, taken from the app's string resources so a label
 * is never written into the code that composes it.
 */
data class UnitLabels(
    val hours: String,
    val minutes: String,
    val seconds: String,
    val percent: String,
    val beatsPerMinute: String,
    val milliseconds: String,
    val grams: String,
    val kilograms: String,
    val pounds: String,
    val kilogramsName: String,
    val poundsName: String,
)

/**
 * The one way the app writes a time, a length, a unit and a weight. A screen reads the user's
 * choice, the phone's own settings, the locale and the unit words from the app, and this class
 * turns them into what is drawn. It owns no state and reads no Android setting itself, so what it
 * writes is a pure function of its arguments.
 *
 * It has one small function per thing it writes on purpose: a single place for every unit word and
 * every clock is what makes them agree across the screens.
 */
@Suppress("TooManyFunctions")
class Formats(
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    val units: Units = Units.SYSTEM,
    val locale: Locale = Locale.getDefault(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * The phone's own 12/24-hour choice, or null to follow [locale]. A preview or a test that draws
     * a screen without the app around it has the second.
     */
    private val phoneIs24Hour: Boolean? = null,
    private val labels: UnitLabels,
) {
  /** Whether a time of day is written with a 24-hour clock. */
  val is24Hour: Boolean =
      when (timeFormat) {
        TimeFormat.SYSTEM -> phoneIs24Hour ?: localeUses24Hour(locale)
        TimeFormat.TWELVE_HOUR -> false
        TimeFormat.TWENTY_FOUR_HOUR -> true
      }

  /** The measurement system the app shows. */
  val measurementSystem: MeasurementSystem =
      when (units) {
        Units.METRIC -> MeasurementSystem.METRIC
        Units.IMPERIAL -> MeasurementSystem.IMPERIAL
        Units.SYSTEM ->
            if (locale.country in ImperialCountries) MeasurementSystem.IMPERIAL
            else MeasurementSystem.METRIC
      }

  private val clockFormatter: DateTimeFormatter =
      DateTimeFormatter.ofPattern(timePattern(locale, is24Hour), locale)

  private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)

  private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", locale)

  /**
   * The clock time of an instant, such as `23:12` or `11:12 PM`. A missing time (zero or less)
   * is a dash, as it has always been.
   */
  fun clockTime(ms: Long): String =
      if (ms <= 0) "-" else Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().format(clockFormatter)

  /** The clock time of a time of day, for a time the user picked rather than an instant. */
  fun clockTime(hour: Int, minute: Int): String =
      LocalTime.of(hour, minute).format(clockFormatter)

  /** The clock time of [minute] minutes after midnight. */
  fun minuteOfDay(minute: Int): String {
    val normalized = (minute % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return clockTime(normalized / 60, normalized % 60)
  }

  /** A date and its clock time, such as `Sat 7 Nov, 23:12`, for a recording's details. */
  fun dateAndTime(ms: Long): String =
      if (ms <= 0) "-"
      else "${Instant.ofEpochMilli(ms).atZone(zone).format(dateFormatter)}, ${clockTime(ms)}"

  /**
   * The date a recording is filed under, such as `Sat 7 Nov`: the night it belongs to (see
   * [nightDate]), so a recording started just after midnight is dated by the evening before, the
   * same as in Trends.
   */
  fun recordingDate(ms: Long): String =
      if (ms <= 0) "-" else nightDate(ms, zone).format(dateFormatter)

  /** A day of a trend, such as `Sat 7 Nov`, for the day a chart's tooltip names. */
  fun dayDate(date: LocalDate): String = date.format(dateFormatter)

  /** A day of a trend in the room an axis label has, such as `7 Nov`. */
  fun shortDate(date: LocalDate): String = date.format(shortDateFormatter)

  /**
   * A length of time, such as `7h 26m`, `2h`, `45m` or `46s`. Under a minute it counts seconds, so
   * a short stage or a fresh recording still reads as something. A negative length counts as zero.
   */
  fun duration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    if (totalSeconds < 60) return "$totalSeconds${labels.seconds}"
    val totalMinutes = totalSeconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
      hours > 0 && minutes > 0 -> "$hours${labels.hours} $minutes${labels.minutes}"
      hours > 0 -> "$hours${labels.hours}"
      else -> "$minutes${labels.minutes}"
    }
  }

  /** An analysis result's number with the unit its kind carries, or null when it has no value. */
  fun itemValue(kind: MetricValueKind, value: Double?): String? {
    val number = value ?: return null
    return when (kind) {
      MetricValueKind.DURATION ->
          if (number.isFinite() && number >= 0) duration((number * 1000).roundToLong()) else "-"
      MetricValueKind.PERCENT -> percent(number)
      MetricValueKind.BPM -> beatsPerMinute(number)
      MetricValueKind.MILLISECONDS -> milliseconds(number)
      MetricValueKind.COUNT -> number.roundToInt().toString()
      MetricValueKind.GRAMS -> String.format(locale, "%.2f %s", number, labels.grams)
    }
  }

  /** A number with the percent sign, such as `86%`. */
  fun percent(value: Double): String = "${value.roundToInt()}${labels.percent}"

  /** A chart label that already reads as a number, with the percent sign. */
  fun percent(text: String): String = "$text${labels.percent}"

  /** A heart rate, such as `58 bpm`. */
  fun beatsPerMinute(value: Double): String =
      "${value.roundToInt()} ${labels.beatsPerMinute}"

  /** A chart label that already reads as a number, with the heart-rate unit. */
  fun beatsPerMinute(text: String): String = "$text ${labels.beatsPerMinute}"

  /** A duration in milliseconds, such as `38 ms`. */
  fun milliseconds(value: Double): String = "${value.roundToInt()} ${labels.milliseconds}"

  /** A chart label that already reads as a number, with the duration unit. */
  fun milliseconds(text: String): String = "$text ${labels.milliseconds}"

  /** A chart label that already reads as a number of hours, such as `7 h`. */
  fun hours(text: String): String = "$text ${labels.hours}"

  /** A weight as it is shown, in kilograms or pounds: the value and its unit. */
  fun weight(kg: Double): String = "${weightNumber(kg)} ${weightUnit}"

  /** A weight as the user reads and edits it, in the unit the app shows, without the unit. */
  fun weightNumber(kg: Double): String = displayNumber(shown(kg))

  /** The unit a weight is shown in: `kg` or `lb`. */
  val weightUnit: String
    get() =
        if (measurementSystem == MeasurementSystem.IMPERIAL) labels.pounds else labels.kilograms

  /** The name of the field the user types a weight into: `Kilograms` or `Pounds`. */
  val weightFieldLabel: String
    get() =
        if (measurementSystem == MeasurementSystem.IMPERIAL) labels.poundsName
        else labels.kilogramsName

  /**
   * The lowest and highest weight the app accepts, in the unit it shows. Both are whole numbers so
   * the range the form states is the range it holds: a pound is not a whole part of a kilogram.
   */
  val weightBounds: ClosedFloatingPointRange<Double>
    get() =
        if (measurementSystem == MeasurementSystem.IMPERIAL) {
          ceil(MIN_WEIGHT_KG * POUNDS_PER_KILOGRAM)..floor(MAX_WEIGHT_KG * POUNDS_PER_KILOGRAM)
        } else {
          MIN_WEIGHT_KG..MAX_WEIGHT_KG
        }

  /**
   * A weight the user typed, in kilograms, or null when it is not a number or outside what the app
   * accepts. The store keeps kilograms, so the conversion happens here, on the way in.
   */
  fun parseWeight(text: String): Double? =
      text
          .trim()
          .replace(',', '.')
          .toDoubleOrNull()
          ?.takeIf { it.isFinite() && it >= weightBounds.start && it <= weightBounds.endInclusive }
          ?.let {
            if (measurementSystem == MeasurementSystem.IMPERIAL) it / POUNDS_PER_KILOGRAM else it
          }

  /** [kg] in the unit the app shows: grams are not a unit the user picks a weight in. */
  private fun shown(kg: Double): Double =
      if (measurementSystem == MeasurementSystem.IMPERIAL) kg * POUNDS_PER_KILOGRAM else kg

  /**
   * A number with at most one decimal, and no decimal when it rounds to a whole one. Rounding
   * before the choice keeps a value the user saves the same when it is shown again.
   */
  private fun displayNumber(value: Double): String {
    val tenths = (value * 10).roundToLong() / 10.0
    return if (abs(tenths - tenths.roundToInt()) < 0.0001) tenths.roundToInt().toString()
    else String.format(locale, "%.1f", tenths)
  }

  private companion object {
    const val MINUTES_PER_DAY = 24 * 60
    const val MIN_WEIGHT_KG = 1.0
    const val MAX_WEIGHT_KG = 500.0
    const val POUNDS_PER_KILOGRAM = 2.2046226218487757

    /** The places the CLDR measurement system counts as imperial; everything else is metric. */
    val ImperialCountries = setOf("US", "LR", "MM")
  }
}

/**
 * The localized SHORT time pattern, with its hour cycle changed to the one the user chose. The
 * am/pm marker is dropped in 24-hour form and added to a locale that does not use one.
 */
private fun timePattern(locale: Locale, is24Hour: Boolean): String {
  val short =
      DateTimeFormatterBuilder.getLocalizedDateTimePattern(
              null,
              FormatStyle.SHORT,
              IsoChronology.INSTANCE,
              locale,
          )
          .replace('\u202F', ' ')
          .replace('\u00A0', ' ')
  return if (is24Hour) twentyFourHour(short) else twelveHour(short)
}

private fun twentyFourHour(pattern: String): String =
    pattern
        .replace('h', 'H')
        .replace('k', 'H')
        .replace('K', 'H')
        .replace(Regex("(?<!H)H(?!H)"), "HH")
        .replace(AM_PM, " ")
        .trim()

private fun twelveHour(pattern: String): String {
  val converted =
      pattern.replace(Regex("H{1,2}"), "h").replace(Regex("k{1,2}"), "h").replace(Regex("K{1,2}"), "h")
  return if (converted.contains('a')) converted else "$converted a"
}

/** The am/pm marker a localized pattern carries: a run of `a`, with the space around it. */
private val AM_PM = Regex("\\s*a\\s*")

/** Whether the locale's own clock is a 24-hour one, which is how System behaves without a phone. */
private fun localeUses24Hour(locale: Locale): Boolean {
  val pattern =
      DateTimeFormatterBuilder.getLocalizedDateTimePattern(
          null,
          FormatStyle.SHORT,
          IsoChronology.INSTANCE,
          locale,
      )
  return pattern.contains('H') || pattern.contains('k')
}
