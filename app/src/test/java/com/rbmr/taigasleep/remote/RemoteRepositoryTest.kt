package com.rbmr.taigasleep.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepositoryTest {
  private val prefs = FakePrefs()
  private val box = FakeSecretBox()
  private val api = FakeRemoteApi()
  private var now = 1_700_000_000_000L

  private fun store() = RemoteStore(prefs, box)

  private fun repository() = RemoteRepository(store(), api) { now }

  private fun login(repo: RemoteRepository) = runBlocking {
    repo.login("http://localhost:8090", "me@example.com", "secret")
  }

  @Test
  fun `a passing address check reports the normalized address`() {
    val checked = runBlocking { repository().checkAddress("localhost:8090") }

    assertEquals(AddressCheck.Reachable("http://localhost:8090", insecure = true), checked)
    assertEquals(listOf("health"), api.calls)
    assertNull(store().url)
  }

  @Test
  fun `a wrong url is reported by the address check`() {
    api.healthError = RemoteError.NotTaiga

    val checked = runBlocking { repository().checkAddress("http://localhost:8090") }

    assertEquals(AddressCheck.Failure(RemoteError.NotTaiga), checked)
    assertEquals(listOf("health"), api.calls)
  }

  @Test
  fun `a server that is down is reported by the address check`() {
    api.healthError = RemoteError.Unreachable

    val checked = runBlocking { repository().checkAddress("http://localhost:8090") }

    assertEquals(AddressCheck.Failure(RemoteError.Unreachable), checked)
  }

  @Test
  fun `an unknown host is reported by the address check`() {
    api.healthError = RemoteError.UnknownHost

    val checked = runBlocking { repository().checkAddress("http://typo.localhost") }

    assertEquals(AddressCheck.Failure(RemoteError.UnknownHost), checked)
  }

  @Test
  fun `an invalid address is reported without a network call`() {
    val checked = runBlocking { repository().checkAddress("not a url") }

    assertEquals(AddressCheck.Failure(RemoteError.InvalidUrl), checked)
    assertTrue(api.calls.isEmpty())
  }

  @Test
  fun `plain http is refused on a public address`() {
    val checked = runBlocking { repository().checkAddress("http://sleep.example.com") }

    assertEquals(AddressCheck.Failure(RemoteError.InsecureUrl), checked)
    assertTrue(api.calls.isEmpty())
  }

  @Test
  fun `a passing login saves the address, the email and the sealed token`() {
    val repo = repository()
    val result = login(repo)

    assertEquals(CheckResult.Success("me@example.com", now), result)
    assertEquals(RemoteStatus.Connected("me@example.com", now), repo.status.value)
    assertEquals("http://localhost:8090", store().url)
    assertEquals("me@example.com", store().email)
    assertEquals("token-1", store().token)

    // The preferences hold ciphertext, never the token itself.
    assertTrue(prefs.strings.getValue("token") != "token-1")
    // Login does not repeat the address ping.
    assertEquals(listOf("login:me@example.com", "auth-check:token-1"), api.calls)
  }

  @Test
  fun `the state and the token survive an app restart`() {
    login(repository())

    val restarted = RemoteRepository(store(), api) { now }
    assertEquals(RemoteStatus.Connected("me@example.com", now), restarted.status.value)
    assertEquals("token-1", store().token)
  }

  @Test
  fun `a wrong password is reported at the login step`() {
    api.loginError = RemoteError.BadCredentials

    val result = login(repository())

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.BadCredentials), result)
    assertNull(store().url)
    assertEquals(listOf("login:me@example.com"), api.calls)
  }

  @Test
  fun `a rejected token is reported at the authenticated step`() {
    api.authError = RemoteError.SessionExpired

    val result = login(repository())

    assertEquals(CheckResult.Failure(RemoteStep.AUTHENTICATED, RemoteError.SessionExpired), result)
    assertNull(store().token)
  }

  @Test
  fun `missing credentials are reported before any network call`() {
    val result = runBlocking { repository().login("http://localhost:8090", "", "") }

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials), result)
    assertTrue(api.calls.isEmpty())
  }

  @Test
  fun `a resume check refreshes the token and updates the check time`() {
    val repo = repository()
    login(repo)
    now += 60_000

    val result = runBlocking { repo.verify() }

    assertEquals(CheckResult.Success("me@example.com", now), result)
    assertEquals("token-2", store().token)
    assertEquals(RemoteStatus.Connected("me@example.com", now), repo.status.value)
    assertEquals("auth-check:token-2", api.calls.last())
  }

  @Test
  fun `an expired session on resume needs a login and drops the token`() {
    val repo = repository()
    login(repo)
    api.refreshError = RemoteError.SessionExpired

    val result = runBlocking { repo.verify() }

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.SessionExpired), result)
    assertNull(store().token)
    val status = repo.status.value as RemoteStatus.LoginNeeded
    assertEquals("me@example.com", status.email)
    assertEquals(RemoteError.SessionExpired, status.reason)
    assertTrue(RemoteRepository(store(), api).status.value is RemoteStatus.LoginNeeded)
  }

  @Test
  fun `an unreachable server on resume does not log the user out`() {
    val repo = repository()
    login(repo)
    api.healthError = RemoteError.Unreachable

    val result = runBlocking { repo.verify() }

    assertEquals(CheckResult.Failure(RemoteStep.REACHABLE, RemoteError.Unreachable), result)
    assertEquals(RemoteStatus.Connected("me@example.com", now), repo.status.value)
    assertEquals("token-1", store().token)
  }

  @Test
  fun `nothing is checked when the remote is not configured`() {
    val repo = repository()

    assertNull(runBlocking { repo.verify() })
    assertTrue(api.calls.isEmpty())
    assertEquals(RemoteStatus.NotConfigured, repo.status.value)
  }

  @Test
  fun `logout clears the token and keeps the address`() {
    val repo = repository()
    login(repo)

    repo.logout()

    assertNull(store().token)
    assertEquals("http://localhost:8090", store().url)
    assertTrue(repo.status.value is RemoteStatus.LoginNeeded)
    assertNull((repo.status.value as RemoteStatus.LoginNeeded).reason)
  }
}

private class FakePrefs : RemotePrefs {
  val strings = mutableMapOf<String, String>()
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

/** Reversible stand-in for the Keystore; the point is that the prefs never see plain text. */
private class FakeSecretBox : SecretBox {
  override fun seal(plain: ByteArray): ByteArray =
      ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

  override fun open(blob: ByteArray): ByteArray =
      ByteArray(blob.size) { (blob[it].toInt() xor 0x5A).toByte() }
}

private class FakeRemoteApi : RemoteApi {
  var healthError: RemoteError? = null
  var loginError: RemoteError? = null
  var refreshError: RemoteError? = null
  var authError: RemoteError? = null
  var loginEmail = "me@example.com"
  val calls = mutableListOf<String>()

  override fun health(baseUrl: String) {
    calls += "health"
    healthError?.let { throw RemoteFailure(it) }
  }

  override fun login(baseUrl: String, email: String, password: String): AuthSession {
    calls += "login:$email"
    loginError?.let { throw RemoteFailure(it) }
    return AuthSession(token = "token-1", email = loginEmail)
  }

  override fun refresh(baseUrl: String, token: String): AuthSession {
    calls += "refresh:$token"
    refreshError?.let { throw RemoteFailure(it) }
    return AuthSession(token = "token-2", email = loginEmail)
  }

  override fun authCheck(baseUrl: String, token: String): AuthUser {
    calls += "auth-check:$token"
    authError?.let { throw RemoteFailure(it) }
    return AuthUser(id = "u1", collection = "users")
  }
}
