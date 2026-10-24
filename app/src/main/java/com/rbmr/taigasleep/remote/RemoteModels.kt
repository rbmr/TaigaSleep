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

/** The state Settings shows. Only these three states exist when nothing is being checked. */
sealed interface RemoteStatus {
  data object NotConfigured : RemoteStatus

  data class Connected(val email: String, val checkedAtMs: Long?) : RemoteStatus

  data class LoginNeeded(
      val email: String?,
      val checkedAtMs: Long?,
      val reason: RemoteError? = null,
  ) : RemoteStatus
}

/** A logged-in session as returned by the backend. */
data class AuthSession(val token: String, val email: String)

data class AuthUser(val id: String, val collection: String)
