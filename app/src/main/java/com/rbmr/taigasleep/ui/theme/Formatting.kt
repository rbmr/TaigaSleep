package com.rbmr.taigasleep.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.insights.Formats
import com.rbmr.taigasleep.insights.TimeFormat
import com.rbmr.taigasleep.insights.UnitLabels
import com.rbmr.taigasleep.insights.Units
import java.time.ZoneId
import java.util.Locale

/**
 * The one formatter the screens draw with. The theme provides it, so a screen reads [LocalFormats]
 * and never builds a format of its own. Reading it outside [TaigaTheme] is a mistake in the code,
 * not a state to fall back from.
 */
val LocalFormats = staticCompositionLocalOf<Formats> {
  error("LocalFormats is read outside TaigaTheme")
}

/** The formatter for [context]'s unit words and the choices it is given. */
internal fun formatsFor(
    context: Context,
    timeFormat: TimeFormat = TimeFormat.SYSTEM,
    units: Units = Units.SYSTEM,
    locale: Locale = Locale.getDefault(),
    phoneIs24Hour: Boolean? = null,
): Formats =
    Formats(
        timeFormat = timeFormat,
        units = units,
        locale = locale,
        zone = ZoneId.systemDefault(),
        phoneIs24Hour = phoneIs24Hour,
        labels =
            UnitLabels(
                hours = context.getString(R.string.unit_hours),
                minutes = context.getString(R.string.unit_minutes),
                seconds = context.getString(R.string.unit_seconds),
                percent = context.getString(R.string.unit_percent),
                beatsPerMinute = context.getString(R.string.unit_bpm),
                milliseconds = context.getString(R.string.unit_ms),
                grams = context.getString(R.string.unit_grams),
                kilograms = context.getString(R.string.unit_kilograms),
                pounds = context.getString(R.string.unit_pounds),
                kilogramsName = context.getString(R.string.unit_kilograms_name),
                poundsName = context.getString(R.string.unit_pounds_name),
            ),
    )

/**
 * The app's formatter, kept current while the app runs: the Display choices, the phone's own
 * 12/24-hour setting and the locale. A change to any of them reaches every screen at once.
 */
@Composable
fun rememberFormats(): Formats {
  val context = LocalContext.current
  val display = context.appContainer.displaySettings
  val timeFormat by display.timeFormat.collectAsStateWithLifecycle()
  val units by display.units.collectAsStateWithLifecycle()
  val locale = LocalLocale.current.platformLocale
  val phoneIs24Hour = rememberPhoneIs24Hour()
  return remember(context, timeFormat, units, locale, phoneIs24Hour) {
    formatsFor(
        context = context,
        timeFormat = timeFormat,
        units = units,
        locale = locale,
        phoneIs24Hour = phoneIs24Hour,
    )
  }
}

/**
 * The phone's own 12/24-hour choice, kept current through a ContentObserver, so choosing the
 * system time format follows the phone without a restart.
 */
@Composable
private fun rememberPhoneIs24Hour(): Boolean {
  val context = LocalContext.current
  var is24Hour by remember { mutableStateOf(DateFormat.is24HourFormat(context)) }
  DisposableEffect(context) {
    val resolver = context.contentResolver
    val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
          override fun onChange(selfChange: Boolean) {
            is24Hour = DateFormat.is24HourFormat(context)
          }
        }
    resolver.registerContentObserver(
        Settings.System.getUriFor(Settings.System.TIME_12_24),
        false,
        observer,
    )
    onDispose { resolver.unregisterContentObserver(observer) }
  }
  return is24Hour
}
