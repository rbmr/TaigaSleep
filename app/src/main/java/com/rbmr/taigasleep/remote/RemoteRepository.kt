package com.rbmr.taigasleep.remote

import android.content.Context
import com.rbmr.taigasleep.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
class RemoteRepository internal constructor(
    private val store: RemoteStore,
    private val api: RemoteApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()

  private val _status = MutableStateFlow(store.status())
  val status: StateFlow<RemoteStatus> = _status.asStateFlow()

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
   * address and calls the unauthenticated health endpoint. It never changes the saved session, but
   * its result is the shared connection state, so the field, the status card and the login button
   * all agree.
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
            val error = health(address.baseUrl, "address")
            if (error != null) return@withLock AddressCheck.Failure(error)
            AddressCheck.Reachable(address.baseUrl, address.insecure)
          } finally {
            _checking.value = false
          }
        }
      }

  /**
   * The full check the Remote screen runs when it opens and when it resumes: health first, then
   * the saved token, but only for the saved address and only when health passed. A server that is
   * down never costs the user the token.
   */
  suspend fun check(rawUrl: String): AddressCheck =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          _checking.value = true
          try {
            val address =
                RemoteUrl.parse(rawUrl).getOrElse { e ->
                  return@withLock failedAddress(parseError(e), "open")
                }
            val error = health(address.baseUrl, "open")
            if (error != null) return@withLock AddressCheck.Failure(error)

            val token = store.token
            if (token != null && address.baseUrl == savedBaseUrl()) {
              tokenCheck(address.baseUrl, token, "open")
            }
            AddressCheck.Reachable(address.baseUrl, address.insecure)
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
            val error = health(address.baseUrl, "resume")
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

  /** The unauthenticated alive check. A failure is a server problem, never a login problem. */
  private fun health(baseUrl: String, source: String): RemoteError? =
      try {
        api.health(baseUrl)
        onReachable()
        log(source, true)
        null
      } catch (e: RemoteFailure) {
        onUnreachable(e.error)
        log(source, false, RemoteStep.REACHABLE, e.error)
        e.error
      }

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
  ) {
    val fields =
        buildMap<String, Any?> {
          put("source", source)
          put("ok", ok)
          put("state", stateLabel())
          if (step != null) put("step", step.name.lowercase())
          if (error != null) put("error", error.label)
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
