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
 * Owns the remote configuration and session. Connecting and logging in are two separate steps:
 * [checkAddress] pings the server from the URL field, and [login] exchanges credentials for a token.
 * [verify] re-checks the saved session on launch and resume.
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

  val hasSession: Boolean
    get() = store.token != null

  /**
   * Pings the address typed in the URL field. This is step one, before any login: it validates the
   * address and calls the unauthenticated health endpoint. It never changes the saved session.
   */
  suspend fun checkAddress(rawUrl: String): AddressCheck =
      withContext(Dispatchers.IO) {
        val address =
            RemoteUrl.parse(rawUrl).getOrElse { e ->
              val error = (e as? RemoteUrlException)?.error ?: RemoteError.InvalidUrl
              logAddress(false, error)
              return@withContext AddressCheck.Failure(error)
            }
        try {
          api.health(address.baseUrl)
        } catch (e: RemoteFailure) {
          logAddress(false, e.error)
          return@withContext AddressCheck.Failure(e.error)
        }
        logAddress(true, null)
        AddressCheck.Reachable(address.baseUrl, address.insecure)
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
                  return@withLock result(
                      RemoteStep.REACHABLE,
                      (e as? RemoteUrlException)?.error ?: RemoteError.InvalidUrl,
                  )
                }
            if (email.isBlank() || password.isEmpty()) {
              return@withLock result(RemoteStep.LOGIN, RemoteError.MissingCredentials)
            }

            val session =
                try {
                  api.login(address.baseUrl, email.trim(), password)
                } catch (e: RemoteFailure) {
                  return@withLock result(RemoteStep.LOGIN, e.error, "login")
                }

            try {
              api.authCheck(address.baseUrl, session.token)
            } catch (e: RemoteFailure) {
              return@withLock result(RemoteStep.AUTHENTICATED, e.error, "login")
            }

            val now = clock()
            store.saveSession(address.baseUrl, session, now)
            _status.value = store.status()
            CheckResult.Success(session.email, now).also { log(it, "login") }
          } finally {
            _checking.value = false
          }
        }
      }

  /**
   * Verifies the stored session without a password. Returns null when nothing is configured.
   * A network error leaves the saved state alone; only a rejected token turns it into
   * [RemoteStatus.LoginNeeded].
   */
  suspend fun verify(): CheckResult? =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          val rawUrl = store.url?.takeIf { it.isNotBlank() } ?: return@withLock null
          val address =
              RemoteUrl.parse(rawUrl).getOrElse { e ->
                val error = (e as? RemoteUrlException)?.error ?: RemoteError.InvalidUrl
                needLogin(error)
                return@withLock CheckResult.Failure(RemoteStep.REACHABLE, error)
              }
          val token = store.token
          if (token == null) {
            needLogin(RemoteError.SessionExpired)
            return@withLock CheckResult.Failure(RemoteStep.LOGIN, RemoteError.SessionExpired)
          }

          try {
            api.health(address.baseUrl)
          } catch (e: RemoteFailure) {
            // The server being down does not log the user out.
            return@withLock result(RemoteStep.REACHABLE, e.error, "resume")
          }

          val session =
              try {
                api.refresh(address.baseUrl, token)
              } catch (e: RemoteFailure) {
                if (e.error == RemoteError.SessionExpired || e.error == RemoteError.BadCredentials) {
                  needLogin(e.error)
                }
                return@withLock result(RemoteStep.LOGIN, e.error, "resume")
              }

          try {
            api.authCheck(address.baseUrl, session.token)
          } catch (e: RemoteFailure) {
            if (e.error == RemoteError.SessionExpired) needLogin(e.error)
            return@withLock result(RemoteStep.AUTHENTICATED, e.error, "resume")
          }

          val now = clock()
          store.saveSession(address.baseUrl, session, now)
          _status.value = store.status()
          CheckResult.Success(session.email, now).also { log(it, "resume") }
        }
      }

  fun logout() {
    store.clearToken()
    store.markLoginNeeded(null)
    _status.value = store.status()
    Diagnostics.log("remote_logout")
  }

  private fun needLogin(reason: RemoteError) {
    store.clearToken()
    store.markLoginNeeded(reason)
    _status.value = store.status()
  }

  private fun result(step: RemoteStep, error: RemoteError, source: String? = null): CheckResult {
    val failure = CheckResult.Failure(step, error)
    if (source != null) log(failure, source)
    return failure
  }

  private fun log(result: CheckResult, source: String) {
    val fields =
        when (result) {
          is CheckResult.Success -> mapOf("source" to source, "ok" to true)
          is CheckResult.Failure ->
              mapOf(
                  "source" to source,
                  "ok" to false,
                  "step" to result.step.name.lowercase(),
                  "error" to result.error.label,
              )
        }
    Diagnostics.log("remote_check", fields)
  }

  /** No address, email or token is ever logged. */
  private fun logAddress(ok: Boolean, error: RemoteError?) {
    val fields =
        if (ok) {
          mapOf("source" to "address", "ok" to true)
        } else {
          mapOf("source" to "address", "ok" to false, "error" to (error ?: RemoteError.Unreachable).label)
        }
    Diagnostics.log("remote_check", fields)
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
