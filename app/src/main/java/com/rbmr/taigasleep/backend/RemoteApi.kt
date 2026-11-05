package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
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
    private val client: OkHttpClient = defaultRemoteClient(),
    private val userAgent: String = "taiga-sleep-android/${BuildConfig.VERSION_NAME}",
) : RemoteApi {
  private val json = Json { ignoreUnknownKeys = true }

  override fun version(baseUrl: String): ServerVersion {
    val body =
        try {
          execute(Request.Builder().url(url(baseUrl, VERSION)).get().header("Accept", JSON).build())
        } catch (e: RemoteHttpException) {
          when (e.status) {
            404,
            405 -> return legacyVersion(baseUrl)
            else -> throw RemoteFailure(RemoteError.Http(e.status))
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

  /**
   * A backend of API 1 has no `/v1` routes but announces itself on its old health route. Reading
   * that lets the app report "different API revision" instead of "not a Taiga server".
   */
  private fun legacyVersion(baseUrl: String): ServerVersion {
    val body =
        try {
          execute(
              Request.Builder().url(url(baseUrl, LEGACY_HEALTH)).get().header("Accept", JSON).build()
          )
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            404,
            405 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    val health =
        try {
          json.decodeFromString<VersionDto>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.NotTaiga)
        }
    if (health.service != "taiga-sleep") throw RemoteFailure(RemoteError.NotTaiga)
    return ServerVersion(version = health.version, api = health.api)
  }

  override fun login(baseUrl: String, email: String, password: String): AuthSession {
    val payload = json.encodeToString(LoginRequest(email, password))
    val body =
        try {
          execute(post(url(baseUrl, LOGIN), payload, token = null))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            401,
            403 -> RemoteFailure(RemoteError.BadCredentials)
            404 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    return readSession(body, fallbackEmail = email, wrongRole = RemoteError.BadCredentials)
  }

  override fun refresh(baseUrl: String, token: String): AuthSession {
    val body =
        try {
          execute(post(url(baseUrl, REFRESH), payload = "{}", token = token))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            401,
            403 -> RemoteFailure(RemoteError.SessionExpired)
            404 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    return readSession(body, fallbackEmail = "", wrongRole = RemoteError.SessionExpired)
  }

  override fun authCheck(baseUrl: String, token: String): AuthUser {
    val body =
        try {
          execute(
              Request.Builder()
                  .url(url(baseUrl, ME))
                  .get()
                  .header("Accept", JSON)
                  .header("Authorization", bearer(token))
                  .build()
          )
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            401,
            403 -> RemoteFailure(RemoteError.SessionExpired)
            404 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    val me =
        try {
          json.decodeFromString<MeDto>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.MalformedResponse)
        }
    if (me.id.isBlank()) throw RemoteFailure(RemoteError.MalformedResponse)
    // A worker account is valid on the server but cannot be used by the app.
    if (me.role != ROLE_USER) throw RemoteFailure(RemoteError.SessionExpired)
    return AuthUser(id = me.id, email = me.email, role = me.role)
  }

  private fun readSession(
      body: String,
      fallbackEmail: String,
      wrongRole: RemoteError,
  ): AuthSession {
    val response =
        try {
          json.decodeFromString<AuthResponse>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.MalformedResponse)
        }
    if (response.token.isBlank()) throw RemoteFailure(RemoteError.MalformedResponse)
    // Only an app account may sign in; the worker's token would be refused by every user route.
    if (response.role != ROLE_USER) throw RemoteFailure(wrongRole)
    val email = response.user?.email?.takeIf { it.isNotBlank() } ?: fallbackEmail
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
    const val LEGACY_HEALTH = "/api/taiga/health"
    const val ME = "/v1/me"
    const val LOGIN = "/v1/auth/login"
    const val REFRESH = "/v1/auth/refresh"
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

@Serializable
private data class AuthResponse(
    val token: String = "",
    val role: String = "",
    val user: UserDto? = null,
)

@Serializable
private data class UserDto(
    val email: String = "",
)

@Serializable private data class LoginRequest(val email: String, val password: String)

@Serializable
private data class MeDto(
    val id: String = "",
    val email: String = "",
    val role: String = "",
)

/** The `Authorization` header value; the API wants the token as a bearer credential. */
internal fun bearer(token: String): String = "Bearer $token"

private fun defaultRemoteClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
