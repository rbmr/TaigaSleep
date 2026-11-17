package com.rbmr.taigasleep

import com.rbmr.taigasleep.insights.Formats
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.insights.UnitLabels
import com.rbmr.taigasleep.insights.Units
import java.time.ZoneId
import java.util.Locale

/** The unit words the app's resources carry, for a test that formats without Android. */
val EnglishUnitLabels =
    UnitLabels(
        hours = "h",
        minutes = "m",
        seconds = "s",
        percent = "%",
        beatsPerMinute = "bpm",
        milliseconds = "ms",
        grams = "g",
        kilograms = "kg",
        pounds = "lb",
        kilogramsName = "Kilograms",
        poundsName = "Pounds",
    )

/** The app's one formatter with the values a test names; the rest is the app's default. */
fun testFormats(
    timeFormat: TimeFormat = TimeFormat.SYSTEM,
    units: Units = Units.SYSTEM,
    locale: Locale = Locale.US,
    zone: ZoneId = ZoneId.of("UTC"),
    phoneIs24Hour: Boolean? = null,
): Formats =
    Formats(
        timeFormat = timeFormat,
        units = units,
        locale = locale,
        zone = zone,
        phoneIs24Hour = phoneIs24Hour,
        labels = EnglishUnitLabels,
    )
