package com.rbmr.taigasleep.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The tiny slice of SharedPreferences the store needs; a fake replaces it in unit tests. */
interface RemotePrefs {
  fun getString(key: String): String?

  fun putString(key: String, value: String?)

  fun getLong(key: String): Long?

  fun putLong(key: String, value: Long?)

  class Shared(private val prefs: SharedPreferences) : RemotePrefs {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
      prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun getLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun putLong(key: String, value: Long?) {
      prefs.edit().apply { if (value == null) remove(key) else putLong(key, value) }.apply()
    }
  }
}

/** Authenticated encryption with the key held by the Android Keystore. */
interface SecretBox {
  fun seal(plain: ByteArray): ByteArray

  fun open(blob: ByteArray): ByteArray
}

/**
 * AES-GCM with a non-exportable key in the Android Keystore. Only the ciphertext and its IV touch
 * the preferences file; the key is never part of the app's data, so a filesystem dump alone cannot
 * recover the token.
 */
class KeystoreSecretBox(private val alias: String = DEFAULT_ALIAS) : SecretBox {
  override fun seal(plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plain)
    return ByteArray(1 + iv.size + ciphertext.size).also {
      it[0] = iv.size.toByte()
      iv.copyInto(it, destinationOffset = 1)
      ciphertext.copyInto(it, destinationOffset = 1 + iv.size)
    }
  }

  override fun open(blob: ByteArray): ByteArray {
    if (blob.size < 2) throw GeneralSecurityException("sealed value is too short")
    val ivSize = blob[0].toInt()
    if (ivSize !in 1..MAX_IV_SIZE || blob.size <= 1 + ivSize) {
      throw GeneralSecurityException("sealed value is malformed")
    }
    val iv = blob.copyOfRange(1, 1 + ivSize)
    val ciphertext = blob.copyOfRange(1 + ivSize, blob.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val DEFAULT_ALIAS = "taiga_remote_token"
    const val MAX_IV_SIZE = 64
  }
}

/**
 * Everything the remote needs to survive an app restart. The URL and email are ordinary
 * preferences; the token is sealed by [SecretBox] before it is written.
 */
class RemoteStore(
    private val prefs: RemotePrefs,
    private val secretBox: SecretBox,
) {
  var url: String?
    get() = prefs.getString(KEY_URL)
    set(value) = prefs.putString(KEY_URL, value)

  var email: String?
    get() = prefs.getString(KEY_EMAIL)
    set(value) = prefs.putString(KEY_EMAIL, value)

  var checkedAtMs: Long?
    get() = prefs.getLong(KEY_CHECKED_AT)
    set(value) = prefs.putLong(KEY_CHECKED_AT, value)

  private var loggedIn: Boolean
    get() = prefs.getString(KEY_STATE) == STATE_CONNECTED
    set(value) = prefs.putString(KEY_STATE, if (value) STATE_CONNECTED else STATE_LOGIN_NEEDED)

  private var storedReason: String?
    get() = prefs.getString(KEY_REASON)
    set(value) = prefs.putString(KEY_REASON, value)

  /** Null when nothing is stored, or when the Keystore key was invalidated and the token is lost. */
  var token: String?
    get() {
      val sealed = prefs.getString(KEY_TOKEN) ?: return null
      return try {
        String(secretBox.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
      } catch (_: Exception) {
        clearToken()
        null
      }
    }
    set(value) {
      if (value == null) {
        prefs.putString(KEY_TOKEN, null)
      } else {
        val sealed = secretBox.seal(value.toByteArray(Charsets.UTF_8))
        prefs.putString(KEY_TOKEN, Base64.getEncoder().encodeToString(sealed))
      }
    }

  fun saveAddress(url: String, email: String) {
    this.url = url
    this.email = email
  }

  fun saveSession(url: String, session: AuthSession, checkedAtMs: Long) {
    saveAddress(url, session.email)
    token = session.token
    this.checkedAtMs = checkedAtMs
    storedReason = null
    loggedIn = true
  }

  fun markChecked(checkedAtMs: Long) {
    this.checkedAtMs = checkedAtMs
    storedReason = null
    loggedIn = true
  }

  fun markLoginNeeded(reason: RemoteError?, checkedAtMs: Long? = this.checkedAtMs) {
    this.checkedAtMs = checkedAtMs
    storedReason = reason?.label
    loggedIn = false
  }

  fun clearToken() {
    token = null
    loggedIn = false
  }

  fun status(): RemoteStatus {
    val savedUrl = url?.takeIf { it.isNotBlank() } ?: return RemoteStatus.NotConfigured
    val savedEmail = email?.takeIf { it.isNotBlank() }
    val checked = checkedAtMs
    val hasToken = token != null
    return if (hasToken && loggedIn) {
      RemoteStatus.Connected(email = savedEmail.orEmpty(), checkedAtMs = checked)
    } else {
      RemoteStatus.LoginNeeded(
          email = savedEmail,
          checkedAtMs = checked,
          reason = remoteErrorFromLabel(storedReason),
      )
    }
  }

  private companion object {
    const val KEY_URL = "url"
    const val KEY_EMAIL = "email"
    const val KEY_TOKEN = "token"
    const val KEY_CHECKED_AT = "checked_at_ms"
    const val KEY_STATE = "state"
    const val KEY_REASON = "reason"
    const val STATE_CONNECTED = "connected"
    const val STATE_LOGIN_NEEDED = "login_needed"
  }
}

fun remoteStore(context: Context): RemoteStore =
    RemoteStore(
        RemotePrefs.Shared(
            context.applicationContext.getSharedPreferences("remote", Context.MODE_PRIVATE)
        ),
        KeystoreSecretBox(),
    )
