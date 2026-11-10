package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepositoryTest {
  private val prefs = FakePrefs()
  private val box = FakeSecretBox()
  private val api = FakeRemoteApi()
  private val clients = HttpClients(OkHttpClient())
  private var now = 1_700_000_000_000L

  private fun store() = RemoteStore(prefs, box)

  private fun repository() = RemoteRepository(store(), api, clients, clock = { now })

  private fun login(repo: RemoteRepository) = runBlocking {
    repo.login("http://localhost:8090", "me@example.com", "secret")
  }

  @Test
  fun `a passing address check reports the normalized address`() {
    val checked = runBlocking { repository().checkAddress("localhost:8090") }

    assertEquals(AddressCheck.Reachable("http://localhost:8090", insecure = true), checked)
    assertEquals(listOf("version"), api.calls)
    assertNull(store().url)
  }

  @Test
  fun `a server with another api revision is refused with both numbers`() {
    api.serverApi = BuildConfig.API_VERSION + 1

    val checked = runBlocking { repository().checkAddress("http://localhost:8090") }

    assertEquals(
        AddressCheck.Failure(
            RemoteError.IncompatibleApi(required = BuildConfig.API_VERSION, server = api.serverApi)
        ),
        checked,
    )
    assertNull(store().url)
  }

  @Test
  fun `an outdated server keeps the saved token and reports the mismatch`() {
    login(repository())
    api.serverApi = BuildConfig.API_VERSION - 1

    val repo = RemoteRepository(store(), api, clients, clock = { now })
    val verified = runBlocking { repo.verify() }

    assertEquals(
        CheckResult.Failure(
            RemoteStep.REACHABLE,
            RemoteError.IncompatibleApi(required = BuildConfig.API_VERSION, server = api.serverApi),
        ),
        verified,
    )
    // A version mismatch is not a rejected token, so the login survives it.
    assertEquals("token-1", store().token)
  }

  @Test
  fun `a wrong url is reported by the address check`() {
    api.versionError = RemoteError.NotTaiga

    val checked = runBlocking { repository().checkAddress("http://localhost:8090") }

    assertEquals(AddressCheck.Failure(RemoteError.NotTaiga), checked)
    assertEquals(listOf("version"), api.calls)
  }

  @Test
  fun `a server that is down is reported by the address check`() {
    api.versionError = RemoteError.Unreachable

    val checked = runBlocking { repository().checkAddress("http://localhost:8090") }

    assertEquals(AddressCheck.Failure(RemoteError.Unreachable), checked)
  }

  @Test
  fun `an unknown host is reported by the address check`() {
    api.versionError = RemoteError.UnknownHost

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

    val restarted = RemoteRepository(store(), api, clients, clock = { now })
    assertEquals(RemoteStatus.Connected("me@example.com", now), restarted.status.value)
    assertEquals("token-1", store().token)
  }

  @Test
  fun `a wrong password is reported at the login step`() {
    api.loginError = RemoteError.BadCredentials

    val repo = repository()
    val result = login(repo)

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.BadCredentials), result)
    assertNull(store().url)
    assertEquals(
        RemoteStatus.LoginNeeded(
            email = null,
            checkedAtMs = now,
            reason = RemoteError.BadCredentials,
        ),
        repo.status.value,
    )
    assertEquals(listOf("login:me@example.com"), api.calls)
  }

  @Test
  fun `a rejected token is reported at the authenticated step`() {
    api.authError = RemoteError.SessionExpired

    val repo = repository()
    val result = login(repo)

    assertEquals(CheckResult.Failure(RemoteStep.AUTHENTICATED, RemoteError.SessionExpired), result)
    assertNull(store().token)
    val status = repo.status.value as RemoteStatus.LoginNeeded
    assertEquals(RemoteError.SessionExpired, status.reason)
  }

  @Test
  fun `missing credentials are reported before any network call`() {
    val repo = repository()
    val result = runBlocking { repo.login("http://localhost:8090", "", "") }

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials), result)
    assertTrue(api.calls.isEmpty())
    assertEquals(RemoteStatus.NotConfigured, repo.status.value)
  }

  @Test
  fun `an empty password with no stored token is refused`() {
    val repo = repository()

    val result = runBlocking { repo.login("http://localhost:8090", "me@example.com", "") }

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials), result)
    assertTrue(api.calls.isEmpty())
    assertNull(store().token)
  }

  @Test
  fun `a login that cannot reach the server keeps the stored session`() {
    val repo = repository()
    login(repo)
    api.loginError = RemoteError.Unreachable

    val result = runBlocking { repo.login("http://localhost:8090", "me@example.com", "new") }

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.Unreachable), result)
    assertEquals("token-1", store().token)
    assertTrue(repo.status.value is RemoteStatus.Unreachable)
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
    assertTrue(RemoteRepository(store(), api, clients).status.value is RemoteStatus.LoginNeeded)
  }

  @Test
  fun `an unreachable server on resume reports unreachable and keeps the token`() {
    val repo = repository()
    login(repo)
    now += 3 * 24 * 60 * 60 * 1000L
    api.versionError = RemoteError.Unreachable

    val result = runBlocking { repo.verify() }

    assertEquals(CheckResult.Failure(RemoteStep.REACHABLE, RemoteError.Unreachable), result)
    assertEquals(RemoteStatus.Unreachable(RemoteError.Unreachable, now), repo.status.value)
    assertEquals("token-1", store().token)
    // A restart must not bring the old Connected state back.
    assertEquals(
        RemoteStatus.Unreachable(RemoteError.Unreachable, now),
        RemoteRepository(store(), api, clients).status.value,
    )
  }

  @Test
  fun `the status check reports unreachable and keeps the token`() {
    val repo = repository()
    login(repo)
    now += 5 * 60_000
    api.versionError = RemoteError.Unreachable

    val checked = runBlocking { repo.checkStatus() }

    assertEquals(CheckResult.Failure(RemoteStep.REACHABLE, RemoteError.Unreachable), checked)
    assertEquals(RemoteStatus.Unreachable(RemoteError.Unreachable, now), repo.status.value)
    assertEquals(RemoteState.UNREACHABLE, repo.state.value)
    assertEquals("token-1", store().token)
  }

  @Test
  fun `a recovered server restores the session and advances the check time`() {
    val repo = repository()
    login(repo)
    api.versionError = RemoteError.Unreachable
    runBlocking { repo.checkStatus() }

    api.versionError = null
    now += 60_000
    val checked = runBlocking { repo.checkStatus() }

    assertEquals(CheckResult.Success("me@example.com", now), checked)
    assertEquals(RemoteStatus.Connected("me@example.com", now), repo.status.value)
    assertEquals(RemoteState.CONNECTED, repo.state.value)
    assertEquals("token-2", store().token)
    assertEquals("auth-check:token-2", api.calls.last())
  }

  @Test
  fun `a different address is only checked for its version`() {
    val repo = repository()
    login(repo)
    api.calls.clear()

    runBlocking { repo.checkAddress("http://other.local:8090") }

    // The saved token belongs to the saved address, so it is not sent anywhere else.
    assertEquals(listOf("version"), api.calls)
  }

  @Test
  fun `an unreachable server during the token proof saves no session`() {
    api.authError = RemoteError.Unreachable

    val repo = repository()
    val result = login(repo)

    assertEquals(CheckResult.Failure(RemoteStep.AUTHENTICATED, RemoteError.Unreachable), result)
    assertNull(store().token)
    assertTrue(repo.status.value is RemoteStatus.Unreachable)
  }

  @Test
  fun `a configured remote without a token needs a password`() {
    store().url = "http://localhost:8090"
    now += 1_000

    val repo = repository()
    val result = runBlocking { repo.verify() }

    assertEquals(CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials), result)
    assertEquals(
        RemoteStatus.LoginNeeded(email = null, checkedAtMs = now, reason = null),
        repo.status.value,
    )
    assertEquals(listOf("version"), api.calls)
  }

  @Test
  fun `an unreachable server without a token is still unreachable`() {
    store().url = "http://localhost:8090"
    api.versionError = RemoteError.Unreachable

    val repo = repository()
    val result = runBlocking { repo.verify() }

    assertEquals(CheckResult.Failure(RemoteStep.REACHABLE, RemoteError.Unreachable), result)
    assertEquals(RemoteStatus.Unreachable(RemoteError.Unreachable, now), repo.status.value)
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

  @Test
  fun `an unconfigured remote has no remote`() {
    val repo = repository()

    assertEquals(RemoteState.NO_REMOTE, repo.state.value)
    assertNull(repo.address.value)
  }

  @Test
  fun `a server that answers without a saved session is not logged in`() {
    val repo = repository()

    runBlocking { repo.checkAddress("localhost:8090") }

    // The address is not saved yet, so this is not "connected"; the server simply answers.
    assertNull(store().url)
    assertEquals("http://localhost:8090", repo.address.value)
    assertEquals(RemoteState.NOT_LOGGED_IN, repo.state.value)
  }

  @Test
  fun `a server that cannot be reached is unreachable`() {
    val repo = repository()
    login(repo)
    api.versionError = RemoteError.Unreachable

    runBlocking { repo.checkStatus() }

    assertEquals(RemoteState.UNREACHABLE, repo.state.value)
  }

  @Test
  fun `a passing login is connected`() {
    val repo = repository()

    login(repo)

    assertEquals(RemoteState.CONNECTED, repo.state.value)
  }

  @Test
  fun `a rejected token is not logged in but keeps the remote`() {
    val repo = repository()
    login(repo)
    api.refreshError = RemoteError.SessionExpired

    runBlocking { repo.verify() }

    assertEquals(RemoteState.NOT_LOGGED_IN, repo.state.value)
    assertEquals("http://localhost:8090", repo.address.value)
  }

  @Test
  fun `logging out keeps the remote and drops the session`() {
    val repo = repository()
    login(repo)

    repo.logout()

    assertEquals(RemoteState.NOT_LOGGED_IN, repo.state.value)
    assertEquals("http://localhost:8090", repo.address.value)
  }

  @Test
  fun `another address is reachable but not connected`() {
    val repo = repository()
    login(repo)

    runBlocking { repo.checkAddress("http://other.local:8090") }

    // The stored token belongs to the saved address, so the other server only got the version check.
    assertEquals("http://other.local:8090", repo.address.value)
    assertEquals(RemoteState.NOT_LOGGED_IN, repo.state.value)
  }

  @Test
  fun `a restart resumes connected`() {
    login(repository())

    assertEquals(
        RemoteState.CONNECTED,
        RemoteRepository(store(), api, clients, clock = { now }).state.value,
    )
  }

  @Test
  fun `the status check without a token only asks for the version`() {
    store().url = "http://localhost:8090"
    val repo = repository()

    val result = runBlocking { repo.checkStatus() }

    assertNull(result)
    assertEquals(listOf("version"), api.calls)
    assertEquals(RemoteState.NOT_LOGGED_IN, repo.state.value)
  }

  @Test
  fun `the status check does nothing without an address`() {
    val repo = repository()

    assertNull(runBlocking { repo.checkStatus() })
    assertTrue(api.calls.isEmpty())
    assertEquals(RemoteState.NO_REMOTE, repo.state.value)
  }

  private val account = "http://localhost:8090|u1"

  @Test
  fun `the account is the server address and the server's user id, not the email`() {
    val repo = repository()
    assertNull(repo.accountKey)

    login(repo)

    assertEquals(account, repo.accountKey)
    // The same user under another address book entry is still the same account.
    api.loginEmail = "renamed@example.com"
    login(repo)
    assertEquals(account, repo.accountKey)
  }

  @Test
  fun `another user id on the same server is another account`() {
    val repo = repository()
    login(repo)

    api.userId = "u2"
    login(repo)

    assertEquals("http://localhost:8090|u2", repo.accountKey)
  }

  @Test
  fun `a session carries the account and a client for it`() {
    val repo = repository()
    assertNull(repo.session())

    login(repo)

    assertEquals(account, repo.session()?.account)
  }

  @Test
  fun `ending an account cancels its work before the next one becomes active`() {
    val ended = mutableListOf<String>()
    val repo =
        RemoteRepository(
            store(),
            api,
            clients,
            clock = { now },
            onAccountEnded = { ended += it },
        )
    login(repo)
    assertTrue(ended.isEmpty())

    api.userId = "u2"
    login(repo)

    assertEquals(listOf(account), ended)
    assertEquals("http://localhost:8090|u2", repo.accountKey)

    repo.logout()

    assertEquals(listOf(account, "http://localhost:8090|u2"), ended)
    assertNull(repo.accountKey)
  }

  @Test
  fun `refreshing the same account ends nothing`() {
    val ended = mutableListOf<String>()
    val repo =
        RemoteRepository(
            store(),
            api,
            clients,
            clock = { now },
            onAccountEnded = { ended += it },
        )
    login(repo)

    runBlocking { repo.verify() }

    assertTrue(ended.isEmpty())
  }

  @Test
  fun `a rejected token ends the account so its queued work stops`() {
    val ended = mutableListOf<String>()
    val repo =
        RemoteRepository(
            store(),
            api,
            clients,
            clock = { now },
            onAccountEnded = { ended += it },
        )
    login(repo)
    api.refreshError = RemoteError.SessionExpired

    runBlocking { repo.verify() }

    assertEquals(listOf(account), ended)
    assertNull(store().token)
  }

  @Test
  fun `the stream may run for a working session and for a server that may come back`() {
    val connected = RemoteStatus.Connected("me@example.com", now)
    assertTrue(connected.canStream())
    for (reason in listOf(RemoteError.Unreachable, RemoteError.UnknownHost, RemoteError.Http(503))) {
      assertTrue(RemoteStatus.Unreachable(reason, now).canStream())
    }
  }

  @Test
  fun `the stream does not run without a login or against a server it cannot talk to`() {
    assertFalse(RemoteStatus.NotConfigured.canStream())
    assertFalse(RemoteStatus.LoginNeeded("me@example.com", now, RemoteError.SessionExpired).canStream())
    val incompatible = RemoteError.IncompatibleApi(required = 3, server = 2)
    for (reason in
        listOf(
            incompatible,
            RemoteError.NotTaiga,
            RemoteError.InvalidUrl,
            RemoteError.InsecureUrl,
            RemoteError.MalformedResponse,
        )) {
      assertFalse(reason.label, RemoteStatus.Unreachable(reason, now).canStream())
    }
  }

  @Test
  fun `the stream account follows login, logout and an incompatible server`() = runBlocking {
    val repo = repository()
    assertNull(repo.streamAccount.first())

    login(repo)
    assertEquals(account, repo.streamAccount.first())

    api.serverApi = BuildConfig.API_VERSION + 1
    repo.verify()
    // The session is kept, but the server speaks another revision: no stream.
    assertNull(repo.streamAccount.first())

    api.serverApi = BuildConfig.API_VERSION
    repo.verify()
    assertEquals(account, repo.streamAccount.first())

    repo.logout()
    assertNull(repo.streamAccount.first())
  }

  @Test
  fun `the stream does not connect without a session`() = runBlocking {
    val transport = FakeTransport()
    val repo = RemoteRepository(store(), api, clients, transport, clock = { now })

    assertTrue(repo.events().toList().isEmpty())
    assertTrue(transport.tokens.isEmpty())
  }

  @Test
  fun `the stream connects to the saved address with the saved token`() = runBlocking {
    login(repository())
    val transport = FakeTransport(listOf(RemoteEvent.Connected))
    val repo = RemoteRepository(store(), api, clients, transport, clock = { now })

    val events = repo.events().take(1).toList()

    assertEquals(listOf<RemoteEvent>(RemoteEvent.Connected), events)
    assertEquals(listOf("http://localhost:8090|token-1"), transport.tokens)
  }

  @Test
  fun `the stream does not connect to a server with another api revision`() = runBlocking {
    login(repository())
    api.serverApi = BuildConfig.API_VERSION - 1
    val transport = FakeTransport(listOf(RemoteEvent.Connected))
    val repo = RemoteRepository(store(), api, clients, transport, clock = { now })
    repo.verify()

    assertTrue(repo.events().toList().isEmpty())
    assertTrue(transport.tokens.isEmpty())
  }

  @Test
  fun `a refused token is refreshed like on any resume and the stream uses the new one`() =
      runBlocking {
        login(repository())
        val transport =
            FakeTransport(
                failure = TaigaUnauthorizedException(401, "expired"),
                then = listOf(RemoteEvent.Connected),
            )
        val repo = RemoteRepository(store(), api, clients, transport, clock = { now })
        api.calls.clear()

        val events = repo.events().take(1).toList()

        assertEquals(listOf<RemoteEvent>(RemoteEvent.Connected), events)
        // The refresh machinery ran: version, refresh with the old token, proof with the new one.
        assertEquals(listOf("version", "refresh:token-1", "auth-check:token-2"), api.calls)
        assertEquals(listOf("http://localhost:8090|token-1", "http://localhost:8090|token-2"), transport.tokens)
        assertEquals("token-2", store().token)
      }

  @Test
  fun `a refused token that the server will not refresh drops the session and ends the stream`() =
      runBlocking {
        login(repository())
        val transport =
            FakeTransport(
                failure = TaigaUnauthorizedException(401, "expired"),
                then = listOf(RemoteEvent.Connected),
            )
        val repo = RemoteRepository(store(), api, clients, transport, clock = { now })
        api.refreshError = RemoteError.SessionExpired

        val events = repo.events().toList()

        assertTrue(events.isEmpty())
        assertEquals(1, transport.tokens.size)
        // The same state a rejected token produces everywhere else.
        assertNull(store().token)
        val status = repo.status.value as RemoteStatus.LoginNeeded
        assertEquals(RemoteError.SessionExpired, status.reason)
        assertNull(repo.streamAccount.first())
      }

  @Test
  fun `the address a login would use follows the last check`() {
    val repo = repository()
    assertNull(repo.address.value)

    runBlocking { repo.checkAddress("localhost:8090") }

    assertEquals("http://localhost:8090", repo.address.value)
  }
}

/**
 * A stream that first fails with [failure] (if given), then connects and emits [then], over and over.
 * It records the address and the token of every connection.
 */
private class FakeTransport(
    private val then: List<RemoteEvent> = emptyList(),
    private val failure: Throwable? = null,
) : EventTransport {
  val tokens = mutableListOf<String>()
  private var failed = false

  override fun open(baseUrl: String, token: String): Flow<RemoteEvent> = flow {
    tokens += "$baseUrl|$token"
    if (failure != null && !failed) {
      failed = true
      throw failure
    }
    then.forEach { emit(it) }
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
  var versionError: RemoteError? = null
  var serverApi = BuildConfig.API_VERSION
  var serverVersion = "0.1.0"
  var loginError: RemoteError? = null
  var refreshError: RemoteError? = null
  var authError: RemoteError? = null
  var loginEmail = "me@example.com"
  var userId = "u1"
  val calls = mutableListOf<String>()

  override fun version(baseUrl: String): ServerVersion {
    calls += "version"
    versionError?.let { throw RemoteFailure(it) }
    return ServerVersion(version = serverVersion, api = serverApi)
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
    return AuthUser(id = userId, email = loginEmail, role = "user")
  }
}
