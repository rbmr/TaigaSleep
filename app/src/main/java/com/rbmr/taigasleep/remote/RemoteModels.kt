package com.rbmr.taigasleep.remote

/** The three checks a connection test runs, in order. */
enum class RemoteStep {
  REACHABLE,
  LOGIN,
  AUTHENTICATED,
}

/**
 * Why a remote call failed. Each case has its own user-facing message so a wrong URL, a wrong
 * password, an expired token and a server that is down never look the same.
 */
sealed interface RemoteError {
  data object InvalidUrl : RemoteError

  /** The host name does not resolve, which is almost always a typo in the address. */
  data object UnknownHost : RemoteError

  data object InsecureUrl : RemoteError

  data object MissingCredentials : RemoteError

  /** DNS, connection or timeout: the server cannot be reached at all. */
  data object Unreachable : RemoteError

  /** Something answers at that address, but it is not a Taiga Sleep backend. */
  data object NotTaiga : RemoteError

  data object BadCredentials : RemoteError

  /** The stored token is no longer accepted; the user has to log in again. */
  data object SessionExpired : RemoteError

  /**
   * The server speaks a different API contract revision than this build was made for. Equal values
   * are the only compatible pair, so one side has to be updated: [required] is what this app needs
   * and [server] is what answered.
   */
  data class IncompatibleApi(val required: Int, val server: Int) : RemoteError

  data class Http(val status: Int) : RemoteError

  data object MalformedResponse : RemoteError
}

val RemoteError.label: String
  get() =
      when (this) {
        RemoteError.InvalidUrl -> "invalid_url"
        RemoteError.UnknownHost -> "unknown_host"
        RemoteError.InsecureUrl -> "insecure_url"
        RemoteError.MissingCredentials -> "missing_credentials"
        RemoteError.Unreachable -> "unreachable"
        RemoteError.NotTaiga -> "not_taiga"
        RemoteError.BadCredentials -> "bad_credentials"
        RemoteError.SessionExpired -> "session_expired"
        is RemoteError.IncompatibleApi -> "incompatible_api_${required}_$server"
        is RemoteError.Http -> "http_$status"
        RemoteError.MalformedResponse -> "malformed_response"
      }

fun remoteErrorFromLabel(label: String?): RemoteError? =
    when {
      label == null -> null
      label == RemoteError.InvalidUrl.label -> RemoteError.InvalidUrl
      label == RemoteError.UnknownHost.label -> RemoteError.UnknownHost
      label == RemoteError.InsecureUrl.label -> RemoteError.InsecureUrl
      label == RemoteError.MissingCredentials.label -> RemoteError.MissingCredentials
      label == RemoteError.Unreachable.label -> RemoteError.Unreachable
      label == RemoteError.NotTaiga.label -> RemoteError.NotTaiga
      label == RemoteError.BadCredentials.label -> RemoteError.BadCredentials
      label == RemoteError.SessionExpired.label -> RemoteError.SessionExpired
      label == RemoteError.MalformedResponse.label -> RemoteError.MalformedResponse
      label.startsWith("incompatible_api_") ->
          label.removePrefix("incompatible_api_").split('_').let { parts ->
            val required = parts.getOrNull(0)?.toIntOrNull()
            val server = parts.getOrNull(1)?.toIntOrNull()
            if (required != null && server != null) RemoteError.IncompatibleApi(required, server)
            else null
          }
      label.startsWith("http_") ->
          label.removePrefix("http_").toIntOrNull()?.let { RemoteError.Http(it) }
      else -> null
    }

sealed interface CheckResult {
  data class Success(val email: String, val checkedAtMs: Long) : CheckResult

  data class Failure(val step: RemoteStep, val error: RemoteError) : CheckResult
}

/** The result of pinging the address typed in the URL field, before any login attempt. */
sealed interface AddressCheck {
  data class Reachable(val baseUrl: String, val insecure: Boolean) : AddressCheck

  data class Failure(val error: RemoteError) : AddressCheck
}

/**
 * The state Settings shows. It is always the result of the most recent check, so it can never
 * claim a connection that no check has confirmed.
 */
sealed interface RemoteStatus {
  data object NotConfigured : RemoteStatus

  data class Connected(val email: String, val checkedAtMs: Long?) : RemoteStatus

  /**
   * The backend answered but the stored token is not valid; a password is needed. This is the only
   * state a rejected token produces: a server that is merely down never gets here.
   */
  data class LoginNeeded(
      val email: String?,
      val checkedAtMs: Long?,
      val reason: RemoteError? = null,
  ) : RemoteStatus

  /**
   * The last check failed before a token could be proven, either because the server could not be
   * reached or because it speaks another API contract. The stored token is kept untouched.
   */
  data class Unreachable(val reason: RemoteError, val checkedAtMs: Long?) : RemoteStatus
}

/**
 * The one status the Status page reports. It is derived from [RemoteStatus] and the address the
 * last check ran against, so it never claims a connection that no check confirmed:
 *
 * - [NO_REMOTE]: no address has been checked or saved.
 * - [UNREACHABLE]: the last check could not reach the address. The token, if any, is kept.
 * - [NOT_LOGGED_IN]: the server answers but no valid token is stored for it.
 * - [CONNECTED]: the server answers and the token passed the authenticated check.
 */
enum class RemoteState {
  NO_REMOTE,
  UNREACHABLE,
  NOT_LOGGED_IN,
  CONNECTED,
}

/** A logged-in session as returned by the backend. */
data class AuthSession(val token: String, val email: String)

/** What a Taiga server reports about itself on the public version route. */
data class ServerVersion(val version: String, val api: Int)

data class AuthUser(val id: String, val email: String, val role: String)
