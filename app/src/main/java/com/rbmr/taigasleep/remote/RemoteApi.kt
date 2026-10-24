package com.rbmr.taigasleep.remote

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
  /** The unauthenticated alive check. Throws [RemoteFailure]. */
  fun health(baseUrl: String)

  /** Exchanges the email and password for a token. */
  fun login(baseUrl: String, email: String, password: String): AuthSession

  /** Extends the life of an existing token. */
  fun refresh(baseUrl: String, token: String): AuthSession

  /** An authenticated query that proves the token is accepted. */
  fun authCheck(baseUrl: String, token: String): AuthUser
}

class RemoteFailure(val error: RemoteError) : Exception(error.label)

class RemoteHttpException(val status: Int) : Exception("HTTP $status")

/** OkHttp plus kotlinx.serialization against the PocketBase routes from the backend README. */
class OkHttpRemoteApi(
    private val client: OkHttpClient = defaultRemoteClient(),
    private val userAgent: String = "taiga-sleep-android/${BuildConfig.VERSION_NAME}",
) : RemoteApi {
  private val json = Json { ignoreUnknownKeys = true }

  override fun health(baseUrl: String) {
    val body =
        try {
          execute(Request.Builder().url(url(baseUrl, HEALTH)).get().header("Accept", JSON).build())
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
            404,
            405 -> RemoteFailure(RemoteError.NotTaiga)
            else -> RemoteFailure(RemoteError.Http(e.status))
          }
        }
    val health =
        try {
          json.decodeFromString<HealthDto>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.NotTaiga)
        }
    if (health.status != "ok") throw RemoteFailure(RemoteError.NotTaiga)
    // The service name is what tells a Taiga backend apart from any other "ok" endpoint.
    if (health.service != "taiga-sleep") throw RemoteFailure(RemoteError.NotTaiga)
  }

  override fun login(baseUrl: String, email: String, password: String): AuthSession {
    val payload = json.encodeToString(LoginRequest(email, password))
    val body =
        try {
          execute(post(url(baseUrl, LOGIN), payload, token = null))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
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
          execute(post(url(baseUrl, REFRESH), payload = "{}", token = token))
        } catch (e: RemoteHttpException) {
          throw when (e.status) {
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
          execute(
              Request.Builder()
                  .url(url(baseUrl, AUTH_CHECK))
                  .get()
                  .header("Accept", JSON)
                  .header("Authorization", token)
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
    val checked =
        try {
          json.decodeFromString<AuthCheckDto>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.MalformedResponse)
        }
    if (!checked.ok) throw RemoteFailure(RemoteError.MalformedResponse)
    return AuthUser(id = checked.id, collection = checked.collection)
  }

  private fun readSession(body: String, fallbackEmail: String): AuthSession {
    val response =
        try {
          json.decodeFromString<AuthResponse>(body)
        } catch (_: Exception) {
          throw RemoteFailure(RemoteError.MalformedResponse)
        }
    if (response.token.isBlank()) throw RemoteFailure(RemoteError.MalformedResponse)
    val email = response.record?.email?.takeIf { it.isNotBlank() } ?: fallbackEmail
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
    if (token != null) builder.header("Authorization", token)
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
    const val HEALTH = "/api/taiga/health"
    const val AUTH_CHECK = "/api/taiga/auth-check"
    const val LOGIN = "/api/collections/users/auth-with-password"
    const val REFRESH = "/api/collections/users/auth-refresh"
    const val JSON = "application/json"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

@Serializable private data class HealthDto(val status: String = "", val service: String = "")
@Serializable
private data class AuthResponse(
    val token: String = "",
    val record: UserDto? = null,
)

@Serializable
private data class UserDto(
    val email: String = "",
)

@Serializable private data class LoginRequest(val identity: String, val password: String)

@Serializable
private data class AuthCheckDto(
    val ok: Boolean = false,
    val id: String = "",
    val collection: String = "",
)

private fun defaultRemoteClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
