package com.rbmr.taigasleep.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.profile.PersonalDataStore
import com.rbmr.taigasleep.profile.Sex
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.StateFlow

/**
 * The personal data page's state holder. The store owns the values; this only mirrors them for the
 * screen and forwards what the user changes, so the page can grow with more fields without the
 * composable reaching into the store.
 */
class PersonalDataViewModel(private val store: PersonalDataStore) : ViewModel() {
  val data: StateFlow<PersonalData> = store.data

  fun setDateOfBirth(value: LocalDate?) = store.setDateOfBirth(value)

  fun setSex(value: Sex?) = store.setSex(value)

  fun setWeightKg(value: Double?) = store.setWeightKg(value)

  fun setUsualBedtime(value: LocalTime?) = store.setUsualBedtime(value)

  fun setUsualWakeTime(value: LocalTime?) = store.setUsualWakeTime(value)

  /** Builds the view model from the one store the app container holds. */
  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PersonalDataViewModel(context.appContainer.personalDataStore) as T
  }
}
