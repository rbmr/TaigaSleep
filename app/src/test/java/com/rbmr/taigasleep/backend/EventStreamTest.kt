package com.rbmr.taigasleep.backend

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal fun pbConnect(clientId: String): String =
    "id:$clientId\nevent:PB_CONNECT\ndata:{\"clientId\":\"$clientId\"}\n\n"

internal fun analysisChange(
    id: String = "a1",
    recording: String = "r1",
    status: String = "queued",
    action: String = "update",
): String =
    "id:2\nevent:analyses/*\ndata:{\"action\":\"$action\",\"record\":{\"id\":\"$id\",\"recording\":\"$recording\",\"status\":\"$status\"}}\n\n"

internal fun recordingChange(
    id: String = "r1",
    action: String = "update",
    extra: String = "",
): String =
    "id:3\nevent:recordings/*\ndata:{\"action\":\"$action\",\"record\":{\"id\":\"$id\"$extra}}\n\n"

class SseParserTest {
  private fun parse(text: String): List<SseMessage> =
      readMessages(Buffer().writeUtf8(text)).toList()

  @Test
  fun `comments and the fields that carry no change are ignored`() {
    val messages =
        parse(
            ": a comment\nid: 7\nretry: 100\n\n" +
                "event: analyses/*\ndata: {\"action\":\"update\"}\n\n"
        )

    assertEquals(listOf(SseMessage("analyses/*", "{\"action\":\"update\"}")), messages)
  }

  @Test
  fun `data lines are joined with a newline and one leading space is dropped`() {
    val messages = parse("event: x\ndata: one\ndata:  two\ndata:three\n\n")

    assertEquals(listOf(SseMessage("x", "one\n two\nthree")), messages)
  }

  @Test
  fun `carriage returns before the line feed are not part of a line`() {
    val messages = parse("event: x\r\ndata: 1\r\n\r\n")

    assertEquals(listOf(SseMessage("x", "1")), messages)
  }

  @Test
  fun `a message without an event name is a plain message`() {
    assertEquals(listOf(SseMessage("message", "hi")), parse("data: hi\n\n"))
  }

  @Test
  fun `a blank line without data dispatches nothing and forgets the event name`() {
    val messages = parse("event: lonely\n\ndata: later\n\n")

    assertEquals(listOf(SseMessage("message", "later")), messages)
  }

  @Test
  fun `a message cut off before its blank line is not delivered`() {
    assertEquals(emptyList<SseMessage>(), parse("event: x\ndata: 1\n"))
  }

  @Test
  fun `a byte order mark at the start is skipped`() {
    assertEquals(listOf(SseMessage("x", "1")), parse("﻿event: x\ndata: 1\n\n"))
  }

  @Test
  fun `a connect message names the client the subscription is made with`() {
    assertEquals("abc", SseMessage("PB_CONNECT", """{"clientId":"abc"}""").clientId())
    assertNull(SseMessage("PB_CONNECT", """{}""").clientId())
    assertNull(SseMessage("PB_CONNECT", "not json").clientId())
    assertNull(SseMessage("analyses/*", """{"clientId":"abc"}""").clientId())
  }

  @Test
  fun `an analysis change becomes an analysis event`() {
    val event = SseMessage("analyses/*", """{"action":"update","record":{"id":"a1","recording":"r1","status":"succeeded"}}""").toEvent()

    assertEquals(RemoteEvent.AnalysisChanged("a1", "r1", "succeeded"), event)
  }

  @Test
  fun `a deleted analysis is reported as deleted`() {
    val event = SseMessage("analyses/*", """{"action":"delete","record":{"id":"a1","recording":"r1","status":"running"}}""").toEvent()

    assertEquals(RemoteEvent.AnalysisChanged("a1", "r1", "deleted"), event)
  }

  @Test
  fun `a recording change becomes a recording event`() {
    val event = SseMessage("recordings/*", """{"action":"create","record":{"id":"r1"}}""").toEvent()

    assertEquals(RemoteEvent.RecordingChanged("r1", "created"), event)
  }

  @Test
  fun `a deleted recording is reported as deleted`() {
    val event = SseMessage("recordings/*", """{"action":"delete","record":{"id":"r1"}}""").toEvent()

    assertEquals(RemoteEvent.RecordingChanged("r1", "deleted"), event)
  }

  @Test
  fun `fields the app does not know are ignored`() {
    val event =
        SseMessage(
                "recordings/*",
                """{"action":"update","record":{"id":"r1","state":"stopped","extra":[1,2]}}"""
            )
            .toEvent()

    assertEquals(RemoteEvent.RecordingChanged("r1", "updated"), event)
  }

  @Test
  fun `unknown topics and unreadable data are skipped`() {
    assertNull(SseMessage("message", "hello").toEvent())
    assertNull(SseMessage("analyses/*", "not json").toEvent())
    assertNull(SseMessage("analyses/*", """{"action":"update","record":{"status":"queued"}}""").toEvent())
    assertNull(SseMessage("recordings/*", """{"action":"create","record":{}}""").toEvent())
  }
}

class OkHttpEventTransportTest {
  private lateinit var server: MockWebServer
  private val clients = HttpClients(OkHttpClient())
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

  private fun stream(body: String, code: Int = 200) =
      MockResponse()
          .setResponseCode(code)
          .setHeader("Content-Type", "text/event-stream")
          .setBody(body)

  private fun subscribed() = MockResponse().setResponseCode(204)

  @Test
  fun `a stream is subscribed and then carries the changes`() = runBlocking {
    server.enqueue(
        stream(pbConnect("c1") + analysisChange(status = "queued") + recordingChange(action = "create"))
    )
    server.enqueue(subscribed())

    val events = OkHttpEventTransport(clients.events).open(baseUrl, "tok").toList()

    assertEquals(
        listOf(
            RemoteEvent.Connected,
            RemoteEvent.AnalysisChanged("a1", "r1", "queued"),
            RemoteEvent.RecordingChanged("r1", "created"),
        ),
        events,
    )
  }

  @Test
  fun `the request is an authenticated stream and the subscription names the topics`() = runBlocking {
    server.enqueue(stream(pbConnect("secret-client")))
    server.enqueue(subscribed())

    OkHttpEventTransport(clients.events).open(baseUrl, "secret-token").toList()

    val open = server.takeRequest()
    assertEquals("GET", open.method)
    assertEquals("/api/realtime", open.path)
    assertEquals("Bearer secret-token", open.getHeader("Authorization"))
    assertEquals("text/event-stream", open.getHeader("Accept"))
    // A compressing proxy would hold changes back.
    assertEquals("identity", open.getHeader("Accept-Encoding"))

    val subscribe = server.takeRequest()
    assertEquals("POST", subscribe.method)
    assertEquals("/api/realtime", subscribe.path)
    assertEquals("Bearer secret-token", subscribe.getHeader("Authorization"))
    val body = subscribe.body.readUtf8()
    assertTrue(body, body.contains("\"clientId\":\"secret-client\""))
    // The subscription asks only for the fields the screens need and names the client.
    assertTrue(body, body.contains("recordings/*?options="))
    assertTrue(body, body.contains("analyses/*?options="))
    assertTrue(body, body.contains("id%2Crecording%2Cstatus"))
  }

  @Test
  fun `a refused stream token is reported as unauthorized without any event`() {
    for (code in listOf(401, 403)) {
      server.enqueue(MockResponse().setResponseCode(code).setBody("""{"code":"unauthorized"}"""))

      val seen = mutableListOf<RemoteEvent>()
      val failure =
          assertThrows(TaigaUnauthorizedException::class.java) {
            runBlocking { OkHttpEventTransport(clients.events).open(baseUrl, "old").collect { seen += it } }
          }

      assertEquals(code, failure.status)
      assertTrue(seen.isEmpty())
    }
  }

  @Test
  fun `a refused subscription is unauthorized and is not mistaken for a connection`() {
    server.enqueue(stream(pbConnect("c1")))
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))

    val seen = mutableListOf<RemoteEvent>()
    val failure =
        assertThrows(TaigaUnauthorizedException::class.java) {
          runBlocking { OkHttpEventTransport(clients.events).open(baseUrl, "old").collect { seen += it } }
        }

    assertEquals(401, failure.status)
    assertTrue(seen.isEmpty())
  }

  @Test
  fun `another error status is an http failure and not a refused token`() {
    server.enqueue(MockResponse().setResponseCode(503))

    val failure =
        assertThrows(TaigaHttpException::class.java) {
          runBlocking { OkHttpEventTransport(clients.events).open(baseUrl, "tok").toList() }
        }

    assertEquals(503, failure.status)
    assertTrue(failure !is TaigaUnauthorizedException)
  }

  @Test
  fun `an answer that is not a stream is refused`() {
    server.enqueue(
        MockResponse().setHeader("Content-Type", "text/html").setBody("<html>sign in</html>")
    )

    val seen = mutableListOf<RemoteEvent>()
    assertThrows(TaigaMalformedException::class.java) {
      runBlocking { OkHttpEventTransport(clients.events).open(baseUrl, "tok").collect { seen += it } }
    }

    // A page is not a connection: nothing may react to it.
    assertTrue(seen.isEmpty())
  }

  @Test
  fun `a server that is not there is a network failure`() {
    val gone = MockWebServer().apply { start() }
    val url = gone.url("/").toString().trimEnd('/')
    gone.shutdown()

    assertThrows(TaigaNetworkException::class.java) {
      runBlocking { OkHttpEventTransport(clients.events).open(url, "tok").toList() }
    }
  }

  @Test
  fun `a stream that goes silent fails after the read timeout`() {
    val connect = pbConnect("c1")
    server.enqueue(
        stream(connect + analysisChange()).throttleBody(connect.length.toLong(), 1, TimeUnit.SECONDS)
    )
    server.enqueue(subscribed())
    val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

    val seen = mutableListOf<RemoteEvent>()
    assertThrows(TaigaNetworkException::class.java) {
      runBlocking { OkHttpEventTransport(client).open(baseUrl, "tok").collect { seen += it } }
    }

    // It had connected before it went quiet.
    assertEquals(listOf<RemoteEvent>(RemoteEvent.Connected), seen)
  }

  @Test
  fun `the read timeout is longer than the server keeps a connection`() {
    val client = clients.events
    // PocketBase closes every connection after thirty minutes.
    assertTrue(client.readTimeoutMillis > 30 * 60 * 1000)
    assertEquals(2_100_000, client.readTimeoutMillis)
    // The stream has no deadline of its own.
    assertEquals(0, client.callTimeoutMillis)
  }

  @Test
  fun `cancelling the collector closes the connection`() = runBlocking {
    val connect = pbConnect("c1")
    server.enqueue(
        stream(connect + analysisChange()).throttleBody(connect.length.toLong(), 1, TimeUnit.SECONDS)
    )
    server.enqueue(subscribed())
    val client = OkHttpClient()

    val first = withTimeout(5_000) { OkHttpEventTransport(client).open(baseUrl, "tok").first() }

    assertEquals(RemoteEvent.Connected, first)
    // first() cancelled the flow, which has to cancel the call that is still reading.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(20)
    }
    assertEquals(0, client.dispatcher.runningCallsCount())
  }
}

class EventStreamTest {
  private val net = TaigaNetworkException(java.io.IOException("down"))

  /** One scripted connection: what it emits, then how it ends (null is a clean end). */
  private class Attempt(val events: List<RemoteEvent> = emptyList(), val failure: Throwable? = null)

  private class ScriptedTransport(vararg script: Attempt) : EventTransport {
    private val queue = ArrayDeque(script.toList())
    val tokens = mutableListOf<String>()

    override fun open(baseUrl: String, token: String): Flow<RemoteEvent> = flow {
      tokens += token
      val attempt = queue.removeFirstOrNull() ?: awaitCancellation()
      attempt.events.forEach { emit(it) }
      attempt.failure?.let { throw it }
    }
  }

  private val pauses = mutableListOf<Long>()

  private fun stream(
      transport: EventTransport,
      token: () -> String? = { "t1" },
      onUnauthorized: suspend () -> Boolean = { true },
  ) =
      EventStream(
          target = { token()?.let { StreamTarget("http://server", it) } },
          transport = transport,
          onUnauthorized = onUnauthorized,
          sleep = { pauses += it },
      )

  private val connected = RemoteEvent.Connected
  private val analysis = RemoteEvent.AnalysisChanged("a1", "r1", "succeeded")
  private val recording = RemoteEvent.RecordingChanged("r1", "updated")

  @Test
  fun `events pass through and a stream the server ended is opened again`() = runBlocking {
    val transport =
        ScriptedTransport(Attempt(listOf(connected, analysis)), Attempt(listOf(connected, recording)))

    val events = stream(transport).events().take(4).toList()

    assertEquals(listOf(connected, analysis, connected, recording), events)
    assertEquals(listOf(1_000L), pauses)
  }

  @Test
  fun `reconnecting backs off from one second and doubles up to thirty`() = runBlocking {
    val failures = List(8) { Attempt(failure = net) }
    val transport = ScriptedTransport(*failures.toTypedArray(), Attempt(listOf(connected)))

    stream(transport).events().take(1).toList()

    assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000), pauses)
  }

  @Test
  fun `a connection that succeeded starts the backoff over`() = runBlocking {
    val transport =
        ScriptedTransport(
            Attempt(failure = net),
            Attempt(failure = net),
            Attempt(listOf(connected)), // success, then the server ends the stream
            Attempt(failure = net),
            Attempt(listOf(connected)),
        )

    stream(transport).events().take(2).toList()

    assertEquals(listOf(1_000L, 2_000, 1_000, 2_000), pauses)
  }

  @Test
  fun `a stream that fails after events were delivered is replaced`() = runBlocking {
    val transport =
        ScriptedTransport(Attempt(listOf(connected, analysis), failure = net), Attempt(listOf(connected)))

    val events = stream(transport).events().take(3).toList()

    assertEquals(listOf(connected, analysis, connected), events)
  }

  @Test
  fun `failures other than a refused token never touch the session`() = runBlocking {
    var asked = 0
    val transport =
        ScriptedTransport(
            Attempt(failure = TaigaHttpException(503, "busy")),
            Attempt(failure = TaigaMalformedException(IllegalStateException("page"))),
            Attempt(failure = net),
            Attempt(listOf(connected)),
        )

    stream(transport, onUnauthorized = { asked++; true }).events().take(1).toList()

    assertEquals(0, asked)
    assertEquals(3, pauses.size)
  }

  @Test
  fun `a refused token refreshes the session and the stream carries on with the new token`() =
      runBlocking {
        var token = "old"
        val transport =
            ScriptedTransport(
                Attempt(failure = TaigaUnauthorizedException(401, "expired")),
                Attempt(listOf(connected)),
            )

        val events =
            stream(transport, token = { token }, onUnauthorized = { token = "new"; true })
                .events()
                .take(1)
                .toList()

        assertEquals(listOf(connected), events)
        assertEquals(listOf("old", "new"), transport.tokens)
      }

  @Test
  fun `a refused token that cannot be saved ends the stream`() = runBlocking {
    var asked = 0
    val transport =
        ScriptedTransport(
            Attempt(failure = TaigaUnauthorizedException(401, "expired")),
            Attempt(listOf(connected)),
        )

    val events = stream(transport, onUnauthorized = { asked++; false }).events().toList()

    // The session is gone: no more connections, and no pause for a retry that will not come.
    assertTrue(events.isEmpty())
    assertEquals(1, asked)
    assertEquals(1, transport.tokens.size)
    assertTrue(pauses.isEmpty())
  }

  @Test
  fun `a session check that crashes ends the stream instead of the app`() = runBlocking {
    val transport = ScriptedTransport(Attempt(failure = TaigaUnauthorizedException(403, "no")))

    val events =
        stream(transport, onUnauthorized = { throw IllegalStateException("boom") }).events().toList()

    assertTrue(events.isEmpty())
    assertEquals(1, transport.tokens.size)
  }

  @Test
  fun `nothing connects without a session`() = runBlocking {
    val transport = ScriptedTransport(Attempt(listOf(connected)))

    val events = stream(transport, token = { null }).events().toList()

    assertTrue(events.isEmpty())
    assertTrue(transport.tokens.isEmpty())
  }

  @Test
  fun `a stream stops once the session is gone`() = runBlocking {
    var token: String? = "t1"
    val transport = ScriptedTransport(Attempt(listOf(connected)), Attempt(listOf(connected)))

    // Logged out while the pause before the next connection ran.
    val seen = mutableListOf<RemoteEvent>()
    EventStream(
            target = { token?.let { StreamTarget("http://server", it) } },
            transport = transport,
            onUnauthorized = { true },
            sleep = { token = null },
        )
        .events()
        .collect { seen += it }

    assertEquals(listOf(connected), seen)
    assertEquals(1, transport.tokens.size)
  }

  @Test
  fun `cancelling while connected closes the connection and stops`() = runBlocking {
    val open = AtomicInteger()
    val closed = AtomicInteger()
    val transport = EventTransport { _, _ ->
      flow {
        open.incrementAndGet()
        try {
          emit(connected)
          awaitCancellation()
        } finally {
          closed.incrementAndGet()
        }
      }
    }
    val seen = mutableListOf<RemoteEvent>()
    val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
      stream(transport).events().collect { seen += it }
    }
    withTimeout(3_000) { while (seen.isEmpty()) delay(10) }

    job.cancel()
    withTimeout(3_000) { job.join() }

    assertEquals(1, open.get())
    assertEquals(1, closed.get())
    // No pause was scheduled and no second connection was made.
    assertTrue(pauses.isEmpty())
  }

  @Test
  fun `cancelling during the pause stops without another connection`() = runBlocking {
    val transport = ScriptedTransport(Attempt(failure = net), Attempt(listOf(connected)))
    val job = launch(Dispatchers.Default) {
      EventStream(
              target = { StreamTarget("http://server", "t1") },
              transport = transport,
              onUnauthorized = { true },
              sleep = { awaitCancellation() },
          )
          .events()
          .collect {}
    }
    withTimeout(3_000) { while (transport.tokens.isEmpty()) delay(10) }

    job.cancel()
    withTimeout(3_000) { job.join() }

    assertEquals(1, transport.tokens.size)
  }

  @Test
  fun `an error in the collector is not mistaken for a broken connection`() {
    val transport = ScriptedTransport(Attempt(listOf(connected)), Attempt(listOf(connected)))

    val failure =
        assertThrows(IllegalStateException::class.java) {
          runBlocking { stream(transport).events().collect { throw IllegalStateException("boom") } }
        }

    assertEquals("boom", failure.message)
    assertEquals(1, transport.tokens.size)
  }

  @Test
  fun `a dropped connection is opened and subscribed again`() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      val baseUrl = server.url("/").toString().trimEnd('/')
      // Each connection names a client of its own and ends; the second is subscribed again.
      server.enqueue(
          MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody(pbConnect("c1"))
      )
      server.enqueue(MockResponse().setResponseCode(204))
      server.enqueue(
          MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody(pbConnect("c2"))
      )
      server.enqueue(MockResponse().setResponseCode(204))

      val events =
          EventStream(
                  target = { StreamTarget(baseUrl, "tok") },
                  transport = OkHttpEventTransport(OkHttpClient()),
                  onUnauthorized = { false },
                  sleep = {},
              )
              .events()
              .take(2)
              .toList()

      assertEquals(listOf(RemoteEvent.Connected, RemoteEvent.Connected), events)
      val requests = (1..4).map { server.takeRequest() }
      assertEquals(listOf("GET", "POST", "GET", "POST"), requests.map { it.method })
      assertTrue(requests[1].body.readUtf8().contains("\"clientId\":\"c1\""))
      assertTrue(requests[3].body.readUtf8().contains("\"clientId\":\"c2\""))
      // The topics are the same on every connection.
      for (subscribe in listOf(requests[1], requests[3])) {
        assertTrue(subscribe.path, subscribe.path == "/api/realtime")
      }
    } finally {
      server.shutdown()
    }
  }
}
