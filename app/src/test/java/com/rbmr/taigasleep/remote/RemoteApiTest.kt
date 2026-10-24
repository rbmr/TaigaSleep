package com.rbmr.taigasleep.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The real OkHttp client against a fake PocketBase, so routing and error mapping are covered. */
class RemoteApiTest {
  private lateinit var server: MockWebServer
  private val api = OkHttpRemoteApi()

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

  @Test
  fun `health, login and auth check use the documented routes`() {
    server.enqueue(json("""{"status":"ok","service":"taiga-sleep","time":"now"}"""))
    server.enqueue(json("""{"token":"tok-1","record":{"id":"u1","email":"me@example.com"}}"""))
    server.enqueue(json("""{"ok":true,"id":"u1","collection":"users"}"""))

    api.health(baseUrl)
    val session = api.login(baseUrl, "me@example.com", "secret")
    val user = api.authCheck(baseUrl, session.token)

    assertEquals("tok-1", session.token)
    assertEquals("me@example.com", session.email)
    assertEquals("u1", user.id)
    assertEquals("users", user.collection)

    val health = server.takeRequest()
    assertEquals("/api/taiga/health", health.path)
    assertNull(health.getHeader("Authorization"))

    val login = server.takeRequest()
    assertEquals("/api/collections/users/auth-with-password", login.path)
    assertEquals("POST", login.method)
    val loginBody = login.body.readUtf8()
    assertTrue(loginBody.contains("\"identity\":\"me@example.com\""))
    assertTrue(loginBody.contains("\"password\":\"secret\""))

    val check = server.takeRequest()
    assertEquals("/api/taiga/auth-check", check.path)
    assertEquals("tok-1", check.getHeader("Authorization"))
  }

  @Test
  fun `a missing health route means the URL is wrong`() {
    server.enqueue(json("""{"message":"Not Found"}""", code = 404))
    assertEquals(RemoteError.NotTaiga, failure { api.health(baseUrl) })
  }

  @Test
  fun `an html page at the address is not a taiga server`() {
    server.enqueue(
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html/>")
    )
    assertEquals(RemoteError.NotTaiga, failure { api.health(baseUrl) })
  }

  @Test
  fun `a different ok service is not a taiga server`() {
    server.enqueue(json("""{"status":"ok","service":"something-else"}"""))
    assertEquals(RemoteError.NotTaiga, failure { api.health(baseUrl) })
  }

  @Test
  fun `rejected credentials are bad credentials`() {
    server.enqueue(json("""{"message":"Failed to authenticate."}""", code = 400))
    assertEquals(
        RemoteError.BadCredentials,
        failure { api.login(baseUrl, "me@example.com", "wrong") },
    )
  }

  @Test
  fun `an expired refresh token is reported as a session expiry`() {
    server.enqueue(json("""{"message":"Unauthorized"}""", code = 401))
    assertEquals(RemoteError.SessionExpired, failure { api.refresh(baseUrl, "stale") })
  }

  @Test
  fun `an expired token on the authenticated query is a session expiry`() {
    server.enqueue(json("""{"message":"Unauthorized"}""", code = 401))
    assertEquals(RemoteError.SessionExpired, failure { api.authCheck(baseUrl, "stale") })
  }

  @Test
  fun `a server that is down is unreachable`() {
    val dead = MockWebServer()
    dead.start()
    val url = dead.url("/").toString().trimEnd('/')
    dead.shutdown()

    assertEquals(RemoteError.Unreachable, failure { api.health(url) })
  }

  @Test
  fun `an unknown host is distinct from an unreachable server`() {
    assertEquals(
        RemoteError.UnknownHost,
        failure { api.health("http://does-not-exist.invalid") },
    )
  }

  @Test
  fun `a login answer without a token is malformed`() {
    server.enqueue(json("""{"record":{"email":"me@example.com"}}"""))
    assertEquals(
        RemoteError.MalformedResponse,
        failure { api.login(baseUrl, "me@example.com", "secret") },
    )
  }
}
