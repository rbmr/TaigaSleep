package com.rbmr.taigasleep.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.Sex
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.SettingsGroup
import com.rbmr.taigasleep.ui.components.SettingsRow
import com.rbmr.taigasleep.ui.components.TaigaCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The weight the app accepts and sends, in kilograms. */
private const val MIN_WEIGHT_KG = 1.0
private const val MAX_WEIGHT_KG = 500.0

/**
 * Settings → Personal data. The optional values SleepECG uses as model features, kept on the phone.
 * A field can always be left empty or cleared, and the page has room for more fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalDataScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val factory = remember { PersonalDataViewModel.Factory(context) }
  val viewModel: PersonalDataViewModel = viewModel(factory = factory)
  val data by viewModel.data.collectAsStateWithLifecycle()
  var showDate by rememberSaveable { mutableStateOf(false) }
  var showSex by rememberSaveable { mutableStateOf(false) }
  var showWeight by rememberSaveable { mutableStateOf(false) }
  var showBedtime by rememberSaveable { mutableStateOf(false) }
  var showWakeTime by rememberSaveable { mutableStateOf(false) }

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.personal_title)) },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.personal_back),
                )
              }
            },
        )
      },
  ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item {
        SettingsGroup(stringResource(R.string.personal_group)) {
          SettingsRow(
              title = stringResource(R.string.personal_date_of_birth_title),
              subtitle = dateOfBirthLabel(data),
              onClick = { showDate = true },
          )
          SettingsDivider()
          SettingsRow(
              title = stringResource(R.string.personal_sex_title),
              subtitle = sexLabel(data.sex),
              onClick = { showSex = true },
          )
          SettingsDivider()
          SettingsRow(
              title = stringResource(R.string.personal_weight_title),
              subtitle = weightLabel(data),
              onClick = { showWeight = true },
          )
          SettingsDivider()
          SettingsRow(
              title = stringResource(R.string.personal_usual_bedtime_title),
              subtitle = timeLabel(data.usualBedtime),
              onClick = { showBedtime = true },
          )
          SettingsDivider()
          SettingsRow(
              title = stringResource(R.string.personal_usual_wake_time_title),
              subtitle = timeLabel(data.usualWakeTime),
              onClick = { showWakeTime = true },
          )
        }
      }
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.personal_privacy_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Spacer(Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.personal_privacy_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
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
        onSave = viewModel::setWeightKg,
        onDismiss = { showWeight = false },
    )
  }

  if (showBedtime) {
    TimeOfDayDialog(
        title = stringResource(R.string.personal_usual_bedtime_title),
        current = data.usualBedtime,
        defaultHour = 23,
        onSave = viewModel::setUsualBedtime,
        onDismiss = { showBedtime = false },
    )
  }

  if (showWakeTime) {
    TimeOfDayDialog(
        title = stringResource(R.string.personal_usual_wake_time_title),
        current = data.usualWakeTime,
        defaultHour = 7,
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
    DatePicker(state = state)
  }
}

@Composable
private fun SexDialog(current: Sex?, onSave: (Sex?) -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.personal_sex_title)) },
      text = {
        Column {
          Text(
              text = stringResource(R.string.personal_sex_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 8.dp),
          )
          Sex.entries.forEach { sex ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable {
                          onSave(sex)
                          onDismiss()
                        }
                        .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = current == sex, onClick = null)
              Spacer(Modifier.width(8.dp))
              Text(stringResource(sexLabelRes(sex)))
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
private fun WeightDialog(current: Double?, onSave: (Double?) -> Unit, onDismiss: () -> Unit) {
  var text by remember { mutableStateOf(current?.let(::formatWeight).orEmpty()) }
  var invalid by remember { mutableStateOf(false) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.personal_weight_title)) },
      text = {
        OutlinedTextField(
            value = text,
            onValueChange = {
              text = it
              invalid = false
            },
            label = { Text(stringResource(R.string.personal_weight_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = invalid,
            supportingText =
                if (invalid) {
                  { Text(stringResource(R.string.personal_weight_invalid)) }
                } else {
                  null
                },
            singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
            onClick = {
              val value = text.trim().replace(',', '.').toDoubleOrNull()
              if (value == null || value < MIN_WEIGHT_KG || value > MAX_WEIGHT_KG) {
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

/**
 * A time picker for the usual bedtime or wake time. The value is optional and can be cleared; a
 * field that was never set opens at a sensible hour for the field it belongs to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayDialog(
    title: String,
    current: LocalTime?,
    defaultHour: Int,
    onSave: (LocalTime?) -> Unit,
    onDismiss: () -> Unit,
) {
  val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
  val state =
      rememberTimePickerState(
          initialHour = current?.hour ?: defaultHour,
          initialMinute = current?.minute ?: 0,
          is24Hour = is24Hour,
      )

  Dialog(onDismissRequest = onDismiss) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
      Column(
          modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
      ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        TimePicker(state = state)
        Spacer(Modifier.height(8.dp))
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
private fun timeLabel(time: LocalTime?): String {
  val value = time ?: return stringResource(R.string.personal_not_set)
  val locale = LocalLocale.current.platformLocale
  return value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
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
private fun weightLabel(data: PersonalData): String =
    data.weightKg?.let { stringResource(R.string.personal_weight_value, formatWeight(it)) }
        ?: stringResource(R.string.personal_not_set)

private fun formatWeight(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.getDefault(), "%.1f", value)

/** The date picker works in UTC midnight, so the chosen calendar day survives every timezone. */
private fun utcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
