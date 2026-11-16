package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.insights.Formats
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.Sex
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.LocalFormats
import com.rbmr.taigasleep.ui.theme.taiga
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings → Personal details. Optional values that some analysis methods use, kept on the
 * phone. A field can always be left empty or cleared, and the page has room for more fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalDataScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val factory = remember { PersonalDataViewModel.Factory(context) }
  val viewModel: PersonalDataViewModel = viewModel(factory = factory)
  val data by viewModel.data.collectAsStateWithLifecycle()
  val formats = LocalFormats.current
  var showDate by rememberSaveable { mutableStateOf(false) }
  var showSex by rememberSaveable { mutableStateOf(false) }
  var showWeight by rememberSaveable { mutableStateOf(false) }
  var showBedtime by rememberSaveable { mutableStateOf(false) }
  var showWakeTime by rememberSaveable { mutableStateOf(false) }

  TaigaScreen(title = stringResource(R.string.personal_title), onBack = onBack) {
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      ListRow(
          title = stringResource(R.string.personal_date_of_birth_title),
          value = dateOfBirthLabel(data),
          onClick = { showDate = true },
      )
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.personal_sex_title),
          value = sexLabel(data.sex),
          onClick = { showSex = true },
      )
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.personal_weight_title),
          value = weightLabel(data, formats),
          onClick = { showWeight = true },
      )
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.personal_usual_bedtime_title),
          value = timeLabel(data.usualBedtime, formats),
          onClick = { showBedtime = true },
      )
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.personal_usual_wake_time_title),
          value = timeLabel(data.usualWakeTime, formats),
          onClick = { showWakeTime = true },
      )
    }
  }

  if (showDate) {
    DateOfBirthDialog(
        current = data.dateOfBirth,
        onSave = viewModel::setDateOfBirth,
        onDismiss = { showDate = false },
    )
  }

  if (showSex) {
    SexDialog(
        current = data.sex,
        onSave = viewModel::setSex,
        onDismiss = { showSex = false },
    )
  }

  if (showWeight) {
    WeightDialog(
        current = data.weightKg,
        formats = formats,
        onSave = viewModel::setWeightKg,
        onDismiss = { showWeight = false },
    )
  }

  if (showBedtime) {
    TimeOfDayDialog(
        field = PersonalTime.BEDTIME,
        current = data.usualBedtime,
        formats = formats,
        onSave = viewModel::setUsualBedtime,
        onDismiss = { showBedtime = false },
    )
  }

  if (showWakeTime) {
    TimeOfDayDialog(
        field = PersonalTime.WAKE,
        current = data.usualWakeTime,
        formats = formats,
        onSave = viewModel::setUsualWakeTime,
        onDismiss = { showWakeTime = false },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateOfBirthDialog(
    current: LocalDate?,
    onSave: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
  val today = remember { LocalDate.now() }
  val todayUtcMs = remember(today) { utcMillis(today) }
  val state =
      rememberDatePickerState(
          initialSelectedDateMillis = current?.let { utcMillis(it) },
          yearRange = (today.year - 120)..today.year,
          selectableDates =
              remember(today) {
                object : SelectableDates {
                  override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                      utcTimeMillis <= todayUtcMs

                  override fun isSelectableYear(year: Int): Boolean = year <= today.year
                }
              },
      )

  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
            onClick = {
              onSave(
                  state.selectedDateMillis?.let { millis ->
                    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                  }
              )
              onDismiss()
            },
            enabled = state.selectedDateMillis != null,
        ) {
          Text(stringResource(R.string.personal_save))
        }
      },
      dismissButton = {
        TextButton(
            onClick = {
              onSave(null)
              onDismiss()
            },
            enabled = current != null,
        ) {
          Text(stringResource(R.string.personal_remove))
        }
      },
  ) {
    DatePicker(
        state = state,
        title = {
          Text(
              text = stringResource(R.string.personal_body),
              style = MaterialTheme.taiga.text.body,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                  Modifier.padding(
                      start = MaterialTheme.taiga.space.l,
                      end = MaterialTheme.taiga.space.l,
                      top = MaterialTheme.taiga.space.m,
                  ),
          )
        },
    )
  }
}

@Composable
private fun SexDialog(current: Sex?, onSave: (Sex?) -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text(
            text = stringResource(R.string.personal_sex_title),
            style = MaterialTheme.taiga.text.section,
        )
      },
      text = {
        Column {
          Text(
              text = stringResource(R.string.personal_body),
              style = MaterialTheme.taiga.text.body,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = MaterialTheme.taiga.space.s),
          )
          Sex.entries.forEach { sex ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                          onSave(sex)
                          onDismiss()
                        }
                        .padding(vertical = MaterialTheme.taiga.space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = current == sex, onClick = null)
              Spacer(Modifier.width(MaterialTheme.taiga.space.s))
              Text(
                  text = stringResource(sexLabelRes(sex)),
                  style = MaterialTheme.taiga.text.body,
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.personal_cancel))
        }
      },
      dismissButton = {
        TextButton(
            onClick = {
              onSave(null)
              onDismiss()
            },
            enabled = current != null,
        ) {
          Text(stringResource(R.string.personal_remove))
        }
      },
  )
}

@Composable
private fun WeightDialog(
    current: Double?,
    formats: Formats,
    onSave: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
  val prefilled = current?.let(formats::weightNumber).orEmpty()
  var text by remember { mutableStateOf(prefilled) }
  var invalid by remember { mutableStateOf(false) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text(
            text = stringResource(R.string.personal_weight_title),
            style = MaterialTheme.taiga.text.section,
        )
      },
      text = {
        WeightField(
            text = text,
            invalid = invalid,
            formats = formats,
            onChange = {
              text = it
              invalid = false
            },
        )
      },
      confirmButton = {
        TextButton(
            onClick = {
              val value = weightToSave(current, prefilled, text, formats)
              if (value == null) {
                invalid = true
              } else {
                onSave(value)
                onDismiss()
              }
            }
        ) {
          Text(stringResource(R.string.personal_save))
        }
      },
      dismissButton = {
        TextButton(
            onClick = {
              onSave(null)
              onDismiss()
            },
            enabled = current != null,
        ) {
          Text(stringResource(R.string.personal_remove))
        }
      },
  )
}

/** The weight field: the explanation, the number in the unit the app uses and why it was refused. */
@Composable
private fun WeightField(
    text: String,
    invalid: Boolean,
    formats: Formats,
    onChange: (String) -> Unit,
) {
  Column {
    Text(
        text = stringResource(R.string.personal_body),
        style = MaterialTheme.taiga.text.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = MaterialTheme.taiga.space.m),
    )
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(formats.weightFieldLabel) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = invalid,
        supportingText =
            if (invalid) {
              {
                Text(
                    stringResource(
                        R.string.personal_weight_invalid,
                        formats.weightBounds.start.toInt().toString(),
                        formats.weightBounds.endInclusive.toInt().toString(),
                        formats.weightUnit,
                    )
                )
              }
            } else {
              null
            },
        singleLine = true,
    )
  }
}

/**
 * The weight a save keeps: the stored one when the field still reads the value it was given, else
 * what the user typed, in kilograms. Null when the text is not a weight the app accepts.
 */
internal fun weightToSave(
    current: Double?,
    prefilled: String,
    text: String,
    formats: Formats,
): Double? =
    if (text.trim() == prefilled) current else formats.parseWeight(text)

/** The two times on the page, each with the hour a field that was never set opens at. */
internal enum class PersonalTime(val titleRes: Int, val defaultHour: Int) {
  BEDTIME(R.string.personal_usual_bedtime_title, 23),
  WAKE(R.string.personal_usual_wake_time_title, 7),
}

/**
 * A time picker for the usual bedtime or wake time. The value is optional and can be cleared; a
 * field that was never set opens at a sensible hour for the field it belongs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeOfDayDialog(
    field: PersonalTime,
    current: LocalTime?,
    formats: Formats,
    onSave: (LocalTime?) -> Unit,
    onDismiss: () -> Unit,
) {
  val title = stringResource(field.titleRes)
  val state =
      rememberTimePickerState(
          initialHour = current?.hour ?: field.defaultHour,
          initialMinute = current?.minute ?: 0,
          is24Hour = formats.is24Hour,
      )

  Dialog(onDismissRequest = onDismiss) {
    Surface(
        shape = MaterialTheme.taiga.shape.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
      Column(
          modifier = Modifier.padding(MaterialTheme.taiga.space.l).verticalScroll(rememberScrollState()),
      ) {
        Text(text = title, style = MaterialTheme.taiga.text.section)
        Spacer(Modifier.height(MaterialTheme.taiga.space.m))
        TimePicker(state = state)
        Spacer(Modifier.height(MaterialTheme.taiga.space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(
              onClick = {
                onSave(null)
                onDismiss()
              },
              enabled = current != null,
          ) {
            Text(stringResource(R.string.personal_remove))
          }
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.personal_cancel))
          }
          TextButton(
              onClick = {
                onSave(LocalTime.of(state.hour, state.minute))
                onDismiss()
              }
          ) {
            Text(stringResource(R.string.personal_save))
          }
        }
      }
    }
  }
}

@Composable
private fun timeLabel(time: LocalTime?, formats: Formats): String {
  val value = time ?: return stringResource(R.string.personal_not_set)
  return formats.clockTime(value.hour, value.minute)
}

@Composable
private fun dateOfBirthLabel(data: PersonalData): String {
  val date = data.dateOfBirth ?: return stringResource(R.string.personal_not_set)
  val locale = LocalLocale.current.platformLocale
  val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
  return date.format(formatter)
}

@Composable
private fun sexLabel(sex: Sex?): String =
    if (sex == null) stringResource(R.string.personal_not_set)
    else stringResource(sexLabelRes(sex))

private fun sexLabelRes(sex: Sex): Int =
    when (sex) {
      Sex.MALE -> R.string.personal_sex_male
      Sex.FEMALE -> R.string.personal_sex_female
    }

@Composable
private fun weightLabel(data: PersonalData, formats: Formats): String =
    data.weightKg?.let(formats::weight) ?: stringResource(R.string.personal_not_set)

/** The date picker works in UTC midnight, so the chosen calendar day survives every timezone. */
private fun utcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
