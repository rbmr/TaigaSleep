package com.rbmr.taigasleep.diagnostics

import android.app.Application
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Registers the system listeners that explain why the OS may be about to stop us. Each event is
 * one log line; the ones that change a snapshot field also request a settings snapshot.
 */
class EventListeners(private val app: Application) {
  private var lastNetwork: String? = null

  fun register() {
    val actions =
        listOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
            ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
        )
    val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
    ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    registerThermal()
    registerNetwork()
  }

  private val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          val action = intent.action ?: return
          val fields = linkedMapOf<String, Any?>()
          when (action) {
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                fields["doze"] = DeviceState.safe { power().isDeviceIdleMode }
            ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED ->
                fields["note"] = "light doze changed (no public getter)"
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                fields["power_save"] = DeviceState.safe { power().isPowerSaveMode }
            BluetoothAdapter.ACTION_STATE_CHANGED ->
                fields["state"] =
                    Names.bluetoothState(
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    )
            Intent.ACTION_BATTERY_LOW,
            Intent.ACTION_BATTERY_OKAY,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED ->
                fields["battery_pct"] = DeviceState.safe { DeviceState.battery(app)?.get("level_pct") }
          }
          if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_SCREEN_OFF) {
            fields["locked"] = DeviceState.safe { keyguard().isKeyguardLocked }
          }
          Diagnostics.log(eventType(action), fields)
          Diagnostics.snapshot(eventType(action))
          Diagnostics.checkState(eventType(action))
        }
      }

  private fun registerThermal() {
    try {
      power().addThermalStatusListener(Runnable::run) { status ->
        Diagnostics.log("thermal", mapOf("status" to Names.thermal(status)))
        Diagnostics.snapshot("thermal")
      }
    } catch (e: Exception) {
      Diagnostics.log("listener_unavailable", mapOf("listener" to "thermal", "error" to e.toString()))
    }
  }

  private fun registerNetwork() {
    try {
      val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      cm.registerDefaultNetworkCallback(
          object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
              report(transportName(caps))
            }

            override fun onLost(network: Network) {
              report("none")
            }
          }
      )
    } catch (e: Exception) {
      // Usually a missing ACCESS_NETWORK_STATE; see docs/diagnostics-manifest.md.
      Diagnostics.log("listener_unavailable", mapOf("listener" to "network", "error" to e.toString()))
    }
  }

  @Synchronized
  private fun report(transport: String) {
    if (transport == lastNetwork) return
    Diagnostics.log("network", mapOf("transport" to transport, "previous" to lastNetwork))
    lastNetwork = transport
  }

  private fun transportName(caps: NetworkCapabilities): String =
      when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
      }

  private fun keyguard() = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

  private fun power() = app.getSystemService(Context.POWER_SERVICE) as PowerManager

  companion object {
    // Protected system broadcast without a public constant.
    const val ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED =
        "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED"

    fun eventType(action: String): String =
        when (action) {
          Intent.ACTION_SCREEN_ON -> "screen_on"
          Intent.ACTION_SCREEN_OFF -> "screen_off"
          Intent.ACTION_POWER_CONNECTED -> "power_connected"
          Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
          Intent.ACTION_BATTERY_LOW -> "battery_low"
          Intent.ACTION_BATTERY_OKAY -> "battery_okay"
          Intent.ACTION_TIME_CHANGED -> "time_changed"
          Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
          PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power_save_changed"
          PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze_changed"
          ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED -> "light_doze_changed"
          BluetoothAdapter.ACTION_STATE_CHANGED -> "bluetooth_state"
          else -> action.substringAfterLast('.').lowercase()
        }
  }
}
