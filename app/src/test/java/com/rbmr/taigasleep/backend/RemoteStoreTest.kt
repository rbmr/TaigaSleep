package com.rbmr.taigasleep.backend

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored token is sealed by the Keystore, so opening it is not free. The store keeps the
 * decrypted value for the life of the process and drops it as soon as the token is set or cleared.
 */
class RemoteStoreTest {
  private val prefs = TokenPrefs()
  private val box = CountingSecretBox()

  private fun store() = RemoteStore(prefs, box)

  @Test
  fun `the token is opened once however often it is read`() {
    store().token = "token-1"
    // A new process reads the stored value; from then on it is in memory.
    val store = store()
    box.opened = 0

    repeat(10) { assertEquals("token-1", store.token) }
    store.status()

    assertEquals("the Keystore is asked once per process", 1, box.opened)
  }

  @Test
  fun `a token that was just set is read without the Keystore`() {
    val store = store()

    store.token = "fresh"

    assertEquals(0, box.opened)
    assertEquals("fresh", store.token)
    assertEquals(0, box.opened)
  }

  @Test
  fun `clearing the token drops the kept value`() {
    val store = store()
    store.token = "token-1"
    assertEquals("token-1", store.token)

    store.clearToken()
    box.opened = 0

    assertNull(store.token)
    assertNull(store.token)
    assertEquals("nothing is left to open", 0, box.opened)
  }

  @Test
  fun `a stored token is not kept in the preferences in clear text`() {
    val store = store()
    store.token = "secret"

    val stored = prefs.strings.values.single { it != null }
    assertEquals("secret", String(box.open(Base64.getDecoder().decode(stored)), Charsets.UTF_8))
  }

  @Test
  fun `a token the Keystore cannot open is cleared once and then stays gone`() {
    val store = store()
    store.token = "token-1"
    box.failing = true
    // A new process reads the stored value again.
    val next = store()

    assertNull(next.token)
    assertEquals(1, box.opened)
    assertNull(next.token)
    assertEquals(1, box.opened)
    // The unreadable value was removed, so a later process does not try it again.
    assertNull(store().token)
    assertEquals(1, box.opened)
  }
}

private class TokenPrefs : RemotePrefs {
  val strings = mutableMapOf<String, String?>()
  val longs = mutableMapOf<String, Long>()

  override fun getString(key: String): String? = strings[key]

  override fun putString(key: String, value: String?) {
    if (value == null) strings.remove(key) else strings[key] = value
  }

  override fun getLong(key: String): Long? = longs[key]

  override fun putLong(key: String, value: Long?) {
    if (value == null) longs.remove(key) else longs[key] = value
  }
}

/** Reversible stand-in for the Keystore that counts how often it was asked to open a value. */
private class CountingSecretBox : SecretBox {
  var opened = 0
  var failing = false

  override fun seal(plain: ByteArray): ByteArray =
      ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

  override fun open(blob: ByteArray): ByteArray {
    opened++
    if (failing) throw java.security.GeneralSecurityException("the key was invalidated")
    return ByteArray(blob.size) { (blob[it].toInt() xor 0x5A).toByte() }
  }
}
