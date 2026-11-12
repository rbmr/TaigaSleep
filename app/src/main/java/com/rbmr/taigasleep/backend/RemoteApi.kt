package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The calls the remote check needs. Kept small so the check logic can be tested with a fake. */
interface RemoteApi {
  /**
   * The unauthenticated version query. It tells a Taiga backend apart from any other server and
   * names the API revision it speaks. Throws [RemoteFailure].
   */
  fun version(baseUrl: String): ServerVersion

  /** Exchanges the email and password for a token. */
  fun login(baseUrl: String, email: String, password: String): AuthSession

  /** Extends the life of an existing token. */
  fun refresh(baseUrl: String, token: String): AuthSession

  /** An authenticated query that proves the token is accepted. */
  fun authCheck(baseUrl: String, token: String): AuthUser
}

class RemoteFailure(val error: RemoteError) : Exception(error.label)

class RemoteHttpException(val status: Int) : Exception("HTTP $status")

/** OkHttp plus kotlinx.serialization against the `/v1` routes from docs/api.md. */
class OkHttpRemoteApi(
    private val client: OkHttpClient,
    private val userAgent: String = "taiga-sleep-android/${BuildConfig.VERSION_NAME}",
) : RemoteApi {
  private val json = Json { ignoreUnknownKeys = true }

  override fun version(baseUrl: String): ServerVersion {
    val body =
        try {
          execute(Request.Builder().url(url(baseUrl, VERSION)).get().header("Accept", JSON).build())
        } catch (e: RemoteHttpException) {
          // The version route is the whole contract's front door: without it the address is not
          // this project's backend.
          throw when (e.status) {
            404,
            405 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    val version =
        try {
          json.decodeFromString<VersionDto>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.NotTaiga)
        }
    // The service name is what tells a Taiga backend apart from any other JSON endpoint.
    if (version.service != "taiga-sleep") throw RemoteFailure(RemoteError.NotTaiga)
    return ServerVersion(version = version.version, api = version.api)
  }

  override fun login(baseUrl: String, email: String, password: String): AuthSession {
    val payload = json.encodeToString(PbkLoginRequest(identity = email, password = password))
    val body =
        try {
          execute(post(url(baseUrl, USERS_AUTH), payload, token = null))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            // PocketBase answers a wrong password with 400; a worker account does not
            // exist in the users collection and is answered the same way.
            400,
            401,
            403 -> RemoteFailure(RemoteError.BadCredentials)
            404 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    return readSession(body, fallbackEmail = email)
  }

  override fun refresh(baseUrl: String, token: String): AuthSession {
    val body =
        try {
          execute(post(url(baseUrl, USERS_REFRESH), payload = "{}", token = token))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            400,
            401,
            403 -> RemoteFailure(RemoteError.SessionExpired)
            404 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    return readSession(body, fallbackEmail = "")
  }

  override fun authCheck(baseUrl: String, token: String): AuthUser {
    val body =
        try {
          execute(post(url(baseUrl, USERS_REFRESH), payload = "{}", token = token))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            400,
            401,
            403 -> RemoteFailure(RemoteError.SessionExpired)
            404 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    val response =
        try {
          json.decodeFromString<PbkAuthResponse>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.MalformedResponse)
        }
    val record = response.record
    if (record.id.isBlank()) throw RemoteFailure(RemoteError.MalformedResponse)
    return AuthUser(id = record.id, email = record.email, role = ROLE_USER)
  }

  /** Reads PocketBase's auth answer: the token and the account's record. */
  private fun readSession(body: String, fallbackEmail: String): AuthSession {
    val response =
        try {
          json.decodeFromString<PbkAuthResponse>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.MalformedResponse)
        }
    if (response.token.isBlank()) throw RemoteFailure(RemoteError.MalformedResponse)
    val email = response.record.email.takeIf { it.isNotBlank() } ?: fallbackEmail
    if (email.isBlank()) throw RemoteFailure(RemoteError.MalformedResponse)
    return AuthSession(token = response.token, email = email)
  }

  private fun url(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

  private fun post(url: String, payload: String, token: String?): Request {
    val builder =
        Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", JSON)
            .header("User-Agent", userAgent)
    if (token != null) builder.header("Authorization", bearer(token))
    return builder.build()
  }

  private fun execute(request: Request): String {
    val authorized =
        if (request.header("User-Agent") == null) {
          request.newBuilder().header("User-Agent", userAgent).build()
        } else {
          request
        }
    try {
      client.newCall(authorized).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw RemoteHttpException(response.code)
        return body
      }
    } catch (e: RemoteHttpException) {
      throw e
    } catch (e: RemoteFailure) {
      throw e
    } catch (_: java.net.UnknownHostException) {
      throw RemoteFailure(RemoteError.UnknownHost)
    } catch (_: IOException) {
      throw RemoteFailure(RemoteError.Unreachable)
    }
  }

  private companion object {
    const val VERSION = "/v1/version"
    // PocketBase's auth routes for the users collection: the app is a user, never a
    // worker, so a worker account simply has no account here.
    const val USERS_AUTH = "/api/collections/users/auth-with-password"
    const val USERS_REFRESH = "/api/collections/users/auth-refresh"
    const val ROLE_USER = "user"
    const val JSON = "application/json"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

@Serializable
private data class VersionDto(
    val service: String = "",
    val version: String = "",
    val api: Int = 0,
)

/** What PocketBase answers on a password login and on a token refresh. */
@Serializable
private data class PbkAuthResponse(
    val token: String = "",
    val record: PbkAccount = PbkAccount(),
)

@Serializable
private data class PbkAccount(
    val id: String = "",
    val email: String = "",
)

@Serializable private data class PbkLoginRequest(val identity: String, val password: String)

/** The `Authorization` header value; the API wants the token as a bearer credential. */
internal fun bearer(token: String): String = "Bearer $token"
