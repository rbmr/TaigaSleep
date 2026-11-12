package com.rbmr.taigasleep.backend

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The real OkHttp client against a fake server, so routing and error mapping are covered. */
class RemoteApiTest {
  private lateinit var server: MockWebServer
  private val api = OkHttpRemoteApi(HttpClients(OkHttpClient()).remote)

  private val baseUrl: String
    get() = server.url("/").toString().trimEnd('/')

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun json(body: String, code: Int = 200) =
      MockResponse()
          .setResponseCode(code)
          .setHeader("Content-Type", "application/json")
          .setBody(body)

  private fun failure(block: () -> Unit): RemoteError =
      try {
        block()
        fail("expected a RemoteFailure")
        error("unreachable")
      } catch (e: RemoteFailure) {
        e.error
      }

  private fun token(email: String = "me@example.com") =
      json(
          """{"token":"tok-1","record":{"id":"u1","email":"$email","collectionName":"users"}}"""
      )

  @Test
  fun `version, login and the token check use the documented routes`() {
    server.enqueue(json("""{"service":"taiga-sleep","version":"0.3.0","api":2}"""))
    server.enqueue(token())
    server.enqueue(token())

    val serverVersion = api.version(baseUrl)
    val session = api.login(baseUrl, "me@example.com", "secret")
    val user = api.authCheck(baseUrl, session.token)

    assertEquals("0.3.0", serverVersion.version)
    assertEquals(2, serverVersion.api)
    assertEquals("tok-1", session.token)
    assertEquals("me@example.com", session.email)
    assertEquals("u1", user.id)
    assertEquals("user", user.role)

    val version = server.takeRequest()
    assertEquals("GET", version.method)
    assertEquals("/v1/version", version.path)
    assertNull(version.getHeader("Authorization"))

    val login = server.takeRequest()
    assertEquals("/api/collections/users/auth-with-password", login.path)
    assertEquals("POST", login.method)
    assertNull(login.getHeader("Authorization"))
    val loginBody = login.body.readUtf8()
    assertTrue(loginBody.contains("\"identity\":\"me@example.com\""))
    assertTrue(loginBody.contains("\"password\":\"secret\""))

    val check = server.takeRequest()
    assertEquals("/api/collections/users/auth-refresh", check.path)
    assertEquals("POST", check.method)
    assertEquals("Bearer tok-1", check.getHeader("Authorization"))
  }

  @Test
  fun `a refresh sends the stored token as a bearer credential and returns the new one`() {
    server.enqueue(token())

    val session = api.refresh(baseUrl, "stored")

    assertEquals("tok-1", session.token)
    assertEquals("me@example.com", session.email)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/collections/users/auth-refresh", request.path)
    assertEquals("Bearer stored", request.getHeader("Authorization"))
  }

  @Test
  fun `a missing version route means the URL is wrong`() {
    server.enqueue(json("""{"code":"not_found","message":"not found"}""", code = 404))

    assertEquals(RemoteError.NotTaiga, failure { api.version(baseUrl) })

    assertEquals("/v1/version", server.takeRequest().path)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `an html page at the address is not a taiga server`() {
    server.enqueue(
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html/>")
    )
    assertEquals(RemoteError.NotTaiga, failure { api.version(baseUrl) })
  }

  @Test
  fun `the version route reports the server version and contract revision`() {
    server.enqueue(json("""{"service":"taiga-sleep","version":"0.3.1","api":2}"""))

    val version = api.version(baseUrl)

    assertEquals("0.3.1", version.version)
    assertEquals(2, version.api)
  }

  @Test
  fun `a different service is not a taiga server`() {
    server.enqueue(json("""{"service":"something-else","version":"1","api":2}"""))
    assertEquals(RemoteError.NotTaiga, failure { api.version(baseUrl) })
  }

  @Test
  fun `a server error on the version route is an http failure`() {
    server.enqueue(json("""{"code":"internal","message":"internal error"}""", code = 500))
    assertEquals(RemoteError.Http(500), failure { api.version(baseUrl) })
  }

  @Test
  fun `rejected credentials are bad credentials`() {
    // PocketBase answers a failed password login with 400, not 401.
    server.enqueue(json("""{"code":400,"message":"Failed to authenticate.","data":{}}""", 400))
    assertEquals(
        RemoteError.BadCredentials,
        failure { api.login(baseUrl, "me@example.com", "wrong") },
    )
  }

  @Test
  fun `too many failed logins are reported as the http status`() {
    server.enqueue(json("""{"code":429,"message":"try again later","data":{}}""", 429))
    assertEquals(
        RemoteError.Http(429),
        failure { api.login(baseUrl, "me@example.com", "wrong") },
    )
  }

  @Test
  fun `a worker account cannot sign in to the app`() {
    // A worker does not exist in the users collection, so its login is refused there.
    server.enqueue(json("""{"code":400,"message":"Failed to authenticate.","data":{}}""", 400))
    assertEquals(
        RemoteError.BadCredentials,
        failure { api.login(baseUrl, "worker@example.com", "secret") },
    )
  }

  @Test
  fun `an expired refresh token is reported as a session expiry`() {
    server.enqueue(json("""{"code":401,"message":"The request requires valid record authorization token.","data":{}}""", 401))
    assertEquals(RemoteError.SessionExpired, failure { api.refresh(baseUrl, "stale") })
  }

  @Test
  fun `an expired token on the token check is a session expiry`() {
    server.enqueue(json("""{"code":401,"message":"The request requires valid record authorization token.","data":{}}""", 401))
    assertEquals(RemoteError.SessionExpired, failure { api.authCheck(baseUrl, "stale") })
  }

  @Test
  fun `a worker token on the token check is a session expiry`() {
    // The users auth-refresh route refuses a token from another collection.
    server.enqueue(json("""{"code":403,"message":"The authorized record is not allowed to perform this action.","data":{}}""", 403))
    assertEquals(RemoteError.SessionExpired, failure { api.authCheck(baseUrl, "worker-token") })
  }

  @Test
  fun `a server that is down is unreachable`() {
    val dead = MockWebServer()
    dead.start()
    val url = dead.url("/").toString().trimEnd('/')
    dead.shutdown()

    assertEquals(RemoteError.Unreachable, failure { api.version(url) })
  }

  @Test
  fun `an unknown host is distinct from an unreachable server`() {
    assertEquals(
        RemoteError.UnknownHost,
        failure { api.version("http://does-not-exist.invalid") },
    )
  }

  @Test
  fun `a login answer without a token is malformed`() {
    server.enqueue(json("""{"record":{"id":"u1","email":"me@example.com"}}"""))
    assertEquals(
        RemoteError.MalformedResponse,
        failure { api.login(baseUrl, "me@example.com", "secret") },
    )
  }
}
