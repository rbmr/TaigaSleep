package com.rbmr.taigasleep.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rbmr.taigasleep.recording.EndRecordingOnAlarm
import com.rbmr.taigasleep.recording.WakeAlarmSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One choice of the end-recording setting, as the screens show it. */
data class EndRecordingOption(@StringRes val label: Int, val selected: Boolean)

/**
 * The smart alarm settings' state holder: the store owns the choice, this only mirrors it as rows
 * for the screen and forwards what the user picks.
 */
class SmartAlarmSettingsViewModel(private val settings: WakeAlarmSettings) : ViewModel() {
  val options: StateFlow<List<EndRecordingOption>> =
      settings.endRecording
          .map(::optionsFor)
          .stateIn(viewModelScope, SharingStarted.Eagerly, optionsFor(settings.endRecording.value))

  /** Picks the option at [index] of [options]. */
  fun select(index: Int) {
    settings.endRecordingOnAlarm = EndRecordingOnAlarm.entries[index]
  }

  private fun optionsFor(current: EndRecordingOnAlarm): List<EndRecordingOption> =
      EndRecordingOnAlarm.entries.map { EndRecordingOption(it.label, it == current) }

  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SmartAlarmSettingsViewModel(WakeAlarmSettings.get(context)) as T
  }
}
