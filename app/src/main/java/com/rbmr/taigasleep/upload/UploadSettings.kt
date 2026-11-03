package com.rbmr.taigasleep.upload

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The automatic upload policy. Manual upload always ignores this. The default is enabled on
 * Wi-Fi, without requiring charging.
 */
data class AutoUploadSettings(
    val enabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
)

/**
 * Upload preferences. Results are always downloaded when possible; these flags only decide when
 * the app may start an upload by itself.
 *
 * Compression is a separate setting and is on by default: it halves the bytes sent and stored.
 * Users can turn it off for a very fast server or to keep the server-side files directly readable.
 *
 * Raw retention decides when the local stream files of an uploaded recording may be removed.
 * The default, [RawRetentionMode.NEVER], keeps every raw file on the phone.
 */
class UploadSettings private constructor(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _autoUpload = MutableStateFlow(load())
  val autoUpload: StateFlow<AutoUploadSettings> = _autoUpload.asStateFlow()

  private val _compressUploads = MutableStateFlow(prefs.getBoolean(KEY_COMPRESS, true))
  val compressUploads: StateFlow<Boolean> = _compressUploads.asStateFlow()

  private val _rawRetention =
      MutableStateFlow(RawRetentionMode.fromStored(prefs.getString(KEY_RAW_RETENTION, null)))
  val rawRetention: StateFlow<RawRetentionMode> = _rawRetention.asStateFlow()

  fun setEnabled(enabled: Boolean) {
    update(_autoUpload.value.copy(enabled = enabled))
  }

  fun setWifiOnly(wifiOnly: Boolean) {
    update(_autoUpload.value.copy(wifiOnly = wifiOnly))
  }

  fun setChargingOnly(chargingOnly: Boolean) {
    update(_autoUpload.value.copy(chargingOnly = chargingOnly))
  }

  fun setAutoUpload(settings: AutoUploadSettings) {
    update(settings)
  }

  fun setCompressUploads(compress: Boolean) {
    prefs.edit().putBoolean(KEY_COMPRESS, compress).apply()
    _compressUploads.value = compress
  }

  fun setRawRetention(mode: RawRetentionMode) {
    prefs.edit().putString(KEY_RAW_RETENTION, mode.storedValue).apply()
    _rawRetention.value = mode
  }

  private fun update(settings: AutoUploadSettings) {
    prefs
        .edit()
        .putBoolean(KEY_ENABLED, settings.enabled)
        .putBoolean(KEY_WIFI_ONLY, settings.wifiOnly)
        .putBoolean(KEY_CHARGING_ONLY, settings.chargingOnly)
        .apply()
    _autoUpload.value = settings
  }

  private fun load(): AutoUploadSettings {
    if (!prefs.contains(KEY_ENABLED)) {
      migrateLegacyMode()
    }
    return AutoUploadSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
        chargingOnly = prefs.getBoolean(KEY_CHARGING_ONLY, false),
    )
  }

  /** The first build used a single auto_transfer mode. Keep the user's choice on upgrade. */
  private fun migrateLegacyMode() {
    val legacy = prefs.getString(LEGACY_KEY, null) ?: return
    val migrated =
        when (legacy) {
          "ALWAYS" -> AutoUploadSettings(enabled = true, wifiOnly = false, chargingOnly = false)
          "WIFI_ONLY" -> AutoUploadSettings(enabled = true, wifiOnly = true, chargingOnly = false)
          "WIFI_CHARGING" -> AutoUploadSettings(enabled = true, wifiOnly = true, chargingOnly = true)
          "NEVER" -> AutoUploadSettings(enabled = false, wifiOnly = true, chargingOnly = false)
          else -> return
        }
    prefs
        .edit()
        .putBoolean(KEY_ENABLED, migrated.enabled)
        .putBoolean(KEY_WIFI_ONLY, migrated.wifiOnly)
        .putBoolean(KEY_CHARGING_ONLY, migrated.chargingOnly)
        .remove(LEGACY_KEY)
        .apply()
  }

  companion object {
    private const val KEY_ENABLED = "auto_upload_enabled"
    private const val KEY_WIFI_ONLY = "auto_upload_wifi_only"
    private const val KEY_CHARGING_ONLY = "auto_upload_charging_only"
    private const val KEY_COMPRESS = "upload_compress"
    private const val KEY_RAW_RETENTION = "raw_retention"
    private const val LEGACY_KEY = "auto_transfer"

    @Volatile private var instance: UploadSettings? = null

    fun get(context: Context): UploadSettings =
        instance
            ?: synchronized(this) {
              instance ?: UploadSettings(context).also { instance = it }
            }
  }
}
