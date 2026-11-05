package com.rbmr.taigasleep.tracker.polar

import android.content.Context
import com.polar.sdk.api.model.PolarRecordingSecret
import java.security.SecureRandom
import java.util.Base64

/**
 * The secret the Polar SDK needs to read an offline recording. It must be the same one the
 * recording was started with, so it outlives the process in the app's preferences. It is not an
 * account credential: losing it only means a recording on the strap cannot be decrypted.
 */
internal class PolarRecordingSecrets(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("polar_offline", Context.MODE_PRIVATE)

  fun get(): PolarRecordingSecret {
    val stored = prefs.getString(KEY_SECRET, null)
    if (stored != null) {
      runCatching { return PolarRecordingSecret(Base64.getDecoder().decode(stored)) }
      // A corrupt value is replaced; there is nothing useful to recover from it.
    }
    val fresh = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
    prefs.edit().putString(KEY_SECRET, Base64.getEncoder().encodeToString(fresh)).apply()
    return PolarRecordingSecret(fresh)
  }

  private companion object {
    const val KEY_SECRET = "recording_secret"
    const val SECRET_BYTES = 32
  }
}
