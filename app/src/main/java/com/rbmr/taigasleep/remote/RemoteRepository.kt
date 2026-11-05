package com.rbmr.taigasleep.remote

import android.content.Context
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the remote configuration, the session and the one connection state every screen reads.
 *
 * Connecting and logging in are two separate steps: [checkAddress] pings the server from the URL
 * field, and [login] exchanges credentials for a token. [verify] re-checks the saved session and
 * [check] does both checks for the Remote screen. All of them report failures through the same pair
 * of shared paths: a transport failure means the server is unreachable and keeps the token, while a
 * rejected token means a login is needed and drops it.
 *
 * [phase] is the step the setup flow has to show. It is derived here, never in the UI, so a screen
 * that reopens or resumes always lands on the step the saved state requires.
 */
class RemoteRepository internal constructor(
    private val store: RemoteStore,
    private val api: RemoteApi,
    transport: EventTransport = OkHttpEventTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()

  private val _status = MutableStateFlow(store.status())
  val status: StateFlow<RemoteStatus> = _status.asStateFlow()

  /**
   * The address the last check ran against, normalized. It is in memory only: an address that has
   * not been saved is not a fact about the account, but it is what the flow has to work on, so a
   * server that answers and has no token for it is "remote, not logged in" rather than "no remote".
   */
  private var addressInPlay: String? = null

  /** The address a login would use: the one last checked, or the saved one. Null when there is none. */
  private val _address = MutableStateFlow(addressInPlay ?: savedBaseUrl())
  val address: StateFlow<String?> = _address.asStateFlow()

  private val _state = MutableStateFlow(computeState())
  val state: StateFlow<RemoteState> = _state.asStateFlow()

  private val _checking = MutableStateFlow(false)
  val checking: StateFlow<Boolean> = _checking.asStateFlow()

  val savedUrl: String
    get() = store.url.orEmpty()

  val savedEmail: String
    get() = store.email.orEmpty()

  /** Stable local key for account-scoped caches. It is not a secret. */
  val accountKey: String
    get() = "${savedUrl}|${savedEmail}"

  val hasSession: Boolean
    get() = store.token != null

  /**
   * The account the live event stream may run for: [accountKey] while there is a session and the
   * server can be spoken to, null otherwise. It changes when the user logs in, out or into another
   * account, and when the server turns out to speak another API revision, so a collector restarts or
   * stops the stream exactly then. A refresh of the same session does not change it.
   */
  val streamAccount: Flow<String?> =
      _status
          .map { if (hasSession && it.canStream()) accountKey else null }
          .distinctUntilChanged()
          // Reading the token opens the Keystore.
          .flowOn(Dispatchers.IO)

  private val eventStream =
      EventStream(target = ::streamTarget, transport = transport, onUnauthorized = ::sessionUsable)

  /**
   * The server's event stream for the saved session, kept connected until the collector goes away.
   * It does not connect without a session or against a server that is not usable (see
   * [canStream]). A refused token is handled like any other: the session is refreshed by [verify],
   * and dropped when the server no longer accepts it. See [EventStream].
   */
  fun events(): Flow<RemoteEvent> = eventStream.events().flowOn(Dispatchers.IO)

  /**
   * A client for the saved address and token. Null until the user has logged in. Transport failures
   * are allowed here: a server that was unreachable a moment ago may be back, and attempts from
   * other features are how the shared state notices. The token, not the last check, decides.
   */
  fun api(): TaigaClient? {
    val baseUrl = savedUrl.takeIf { it.isNotBlank() } ?: return null
    val token = store.token ?: return null
    return OkHttpTaigaApi(baseUrl = baseUrl, token = token)
  }

  /**
   * Pings the address typed in the URL field. This is step one, before any login: it validates the
   * address and calls the unauthenticated version endpoint. It never changes the saved session, but
   * its result is the shared connection state, so the flow, the address field and the login all
   * agree.
   */
  suspend fun checkAddress(rawUrl: String): AddressCheck =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          _checking.value = true
          try {
            val address =
                RemoteUrl.parse(rawUrl).getOrElse { e ->
                  return@withLock failedAddress(parseError(e), "address")
                }
            addressInPlay = address.baseUrl
            val error = checkVersion(address.baseUrl, "address")
            if (error != null) return@withLock AddressCheck.Failure(error)
            AddressCheck.Reachable(address.baseUrl, address.insecure)
          } finally {
            _checking.value = false
          }
        }
      }

  /**
   * Re-determines the status of the address in play: the version first, then the saved token when the
   * address is the one it belongs to. This is what the Status page's "Check connection" runs, so a
   * server that is down never costs the user the token and a recovered one restores the session.
   * Returns null when there is no address to check.
   */
  suspend fun checkStatus(): CheckResult? =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          _checking.value = true
          try {
            val baseUrl = addressInPlay ?: savedBaseUrl() ?: return@withLock null
            val error = checkVersion(baseUrl, "status")
            if (error != null) return@withLock CheckResult.Failure(RemoteStep.REACHABLE, error)
            val token = store.token
            if (token == null || baseUrl != savedBaseUrl()) return@withLock null
            tokenCheck(baseUrl, token, "status")
          } finally {
            _checking.value = false
          }
        }
      }

  /**
   * Step two: logs in with the password and proves the new token with the authenticated query.
   * Saves the address and the token only when both succeed.
   */
  suspend fun login(rawUrl: String, email: String, password: String): CheckResult =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          _checking.value = true
          try {
            val address =
                RemoteUrl.parse(rawUrl).getOrElse { e ->
                  val error = parseError(e)
                  onUnreachable(error)
                  log("login", false, RemoteStep.REACHABLE, error)
                  return@withLock CheckResult.Failure(RemoteStep.REACHABLE, error)
                }
            if (email.isBlank() || password.isEmpty()) {
              // A local refusal: no request is made and the saved state is left alone.
              log("login", false, RemoteStep.LOGIN, RemoteError.MissingCredentials)
              return@withLock CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials)
            }

            addressInPlay = address.baseUrl
            val session =
                try {
                  api.login(address.baseUrl, email.trim(), password)
                } catch (e: RemoteFailure) {
                  return@withLock failedCall(RemoteStep.LOGIN, e.error, "login")
                }

            try {
              api.authCheck(address.baseUrl, session.token)
            } catch (e: RemoteFailure) {
              return@withLock failedCall(RemoteStep.AUTHENTICATED, e.error, "login")
            }

            val now = clock()
            store.saveSession(address.baseUrl, session, now)
            publish()
            log("login", true)
            CheckResult.Success(session.email, now)
          } finally {
            _checking.value = false
          }
        }
      }

  /**
   * Verifies the stored session without a password. Returns null when nothing is configured.
   * A transport failure marks the server unreachable and leaves the token alone; only a rejected
   * token turns the state into [RemoteStatus.LoginNeeded].
   */
  suspend fun verify(): CheckResult? =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          _checking.value = true
          try {
            val rawUrl = store.url?.takeIf { it.isNotBlank() } ?: return@withLock null
            val address =
                RemoteUrl.parse(rawUrl).getOrElse { e ->
                  val error = parseError(e)
                  onUnreachable(error)
                  log("resume", false, RemoteStep.REACHABLE, error)
                  return@withLock CheckResult.Failure(RemoteStep.REACHABLE, error)
                }
            val token = store.token
            addressInPlay = address.baseUrl
            val error = checkVersion(address.baseUrl, "resume")
            if (error != null) return@withLock CheckResult.Failure(RemoteStep.REACHABLE, error)
            if (token == null) {
              // Configured but never logged in: the password is what is missing, not a session.
              log("resume", false, RemoteStep.LOGIN, RemoteError.MissingCredentials)
              return@withLock CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials)
            }

            tokenCheck(address.baseUrl, token, "resume")
          } finally {
            _checking.value = false
          }
        }
      }

  private fun streamTarget(): StreamTarget? {
    if (!_status.value.canStream()) return null
    val baseUrl = savedUrl.takeIf { it.isNotBlank() } ?: return null
    val token = store.token ?: return null
    return StreamTarget(baseUrl, token)
  }

  /** After a refused token: the same refresh a resume does, then whether a session is left. */
  private suspend fun sessionUsable(): Boolean {
    verify()
    return hasSession && _status.value.canStream()
  }

  fun logout() {
    store.clearToken()
    store.markLoginNeeded(null)
    publish()
    Diagnostics.log("remote_logout")
  }

  /** Refreshes the token and proves it. Only a rejected token needs a new login. */
  private fun tokenCheck(baseUrl: String, token: String, source: String): CheckResult {
    val session =
        try {
          api.refresh(baseUrl, token)
        } catch (e: RemoteFailure) {
          return failedCall(RemoteStep.LOGIN, e.error, source)
        }

    try {
      api.authCheck(baseUrl, session.token)
    } catch (e: RemoteFailure) {
      return failedCall(RemoteStep.AUTHENTICATED, e.error, source)
    }

    val now = clock()
    store.saveSession(baseUrl, session, now)
    publish()
    log(source, true)
    return CheckResult.Success(session.email, now)
  }

  /**
   * The unauthenticated version query, which is also the alive check. A failure is a server
   * problem, never a login problem. The server also has to speak this build's API contract
   * revision, because a mismatch would fail later in ways the user cannot read.
   */
  private fun checkVersion(baseUrl: String, source: String): RemoteError? =
      try {
        val server = api.version(baseUrl)
        val details = serverDetails(server)
        if (server.api != BuildConfig.API_VERSION) {
          val error =
              RemoteError.IncompatibleApi(required = BuildConfig.API_VERSION, server = server.api)
          onUnreachable(error)
          log(source, false, RemoteStep.REACHABLE, error, details)
          error
        } else {
          onReachable()
          log(source, true, extra = details)
          null
        }
      } catch (e: RemoteFailure) {
        onUnreachable(e.error)
        log(source, false, RemoteStep.REACHABLE, e.error)
        e.error
      }

  private fun serverDetails(server: ServerVersion): Map<String, Any?> =
      mapOf("server_version" to server.version, "server_api" to server.api)

  private fun failedAddress(error: RemoteError, source: String): AddressCheck.Failure {
    onUnreachable(error)
    log(source, false, RemoteStep.REACHABLE, error)
    return AddressCheck.Failure(error)
  }

  private fun failedCall(step: RemoteStep, error: RemoteError, source: String): CheckResult {
    if (isAuthFailure(error)) onAuthFailure(error) else onUnreachable(error)
    log(source, false, step, error)
    return CheckResult.Failure(step, error)
  }

  private fun isAuthFailure(error: RemoteError): Boolean =
      error == RemoteError.SessionExpired || error == RemoteError.BadCredentials

  /**
   * The server answered. Restores the session state an earlier outage hid, without verifying it.
   */
  private fun onReachable() {
    store.markReachable(clock())
    publish()
  }

  private fun onUnreachable(error: RemoteError) {
    store.markUnreachable(error, clock())
    publish()
  }

  private fun onAuthFailure(error: RemoteError) {
    store.clearToken()
    onLoginNeeded(error)
  }

  private fun onLoginNeeded(reason: RemoteError?) {
    store.markLoginNeeded(reason, clock())
    publish()
  }

  private fun publish() {
    _status.value = store.status()
    _address.value = addressInPlay ?: savedBaseUrl()
    _state.value = computeState()
  }

  /**
   * The status the Status page shows. A server that cannot be reached is reported as such; a
   * reachable one is connected only when the token that proved itself belongs to it.
   */
  private fun computeState(): RemoteState {
    val inPlay = addressInPlay ?: savedBaseUrl() ?: return RemoteState.NO_REMOTE
    return when {
      _status.value is RemoteStatus.Unreachable -> RemoteState.UNREACHABLE
      _status.value is RemoteStatus.Connected && inPlay == savedBaseUrl() -> RemoteState.CONNECTED
      else -> RemoteState.NOT_LOGGED_IN
    }
  }

  private fun savedBaseUrl(): String? =
      store.url?.takeIf { it.isNotBlank() }?.let { RemoteUrl.parse(it).getOrNull()?.baseUrl }

  private fun parseError(e: Throwable): RemoteError =
      (e as? RemoteUrlException)?.error ?: RemoteError.InvalidUrl

  /** No address, email or token is ever logged. */
  private fun log(
      source: String,
      ok: Boolean,
      step: RemoteStep? = null,
      error: RemoteError? = null,
      extra: Map<String, Any?> = emptyMap(),
  ) {
    val fields =
        buildMap<String, Any?> {
          put("source", source)
          put("ok", ok)
          put("state", stateLabel())
          put("remote", _state.value.name.lowercase())
          if (step != null) put("step", step.name.lowercase())
          if (error != null) put("error", error.label)
          putAll(extra)
        }
    Diagnostics.log("remote_check", fields)
  }

  private fun stateLabel(): String =
      when (_status.value) {
        RemoteStatus.NotConfigured -> "not_configured"
        is RemoteStatus.Connected -> "connected"
        is RemoteStatus.LoginNeeded -> "login_needed"
        is RemoteStatus.Unreachable -> "unreachable"
      }

  companion object {
    @Volatile private var instance: RemoteRepository? = null

    fun of(context: Context): RemoteRepository =
        instance
            ?: synchronized(this) {
              instance
                  ?: RemoteRepository(
                          store = remoteStore(context),
                          api = OkHttpRemoteApi(),
                      )
                      .also { instance = it }
            }
  }
}
