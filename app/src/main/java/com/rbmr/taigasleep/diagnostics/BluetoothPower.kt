package com.rbmr.taigasleep.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's Bluetooth radio, as the system's state broadcast reports it: [enabled] is true only
 * once the adapter is fully on. Null until the system reports a change.
 *
 * The broadcast is already listened to here for the diagnostics log; a live recording watches the
 * same state to reconnect as soon as the radio is back instead of waiting for a stall.
 */
object BluetoothPower {
  private val _enabled = MutableStateFlow<Boolean?>(null)
  val enabled: StateFlow<Boolean?> = _enabled.asStateFlow()

  fun report(on: Boolean) {
    _enabled.value = on
  }
}
