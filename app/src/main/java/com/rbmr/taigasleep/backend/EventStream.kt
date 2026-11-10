package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource

/**
 * What the server's event stream (`GET /v1/events`, docs/api.md) tells the app. An event carries ids
 * and a status, never data, and is never replayed: whoever shows something refetches it.
 */
sealed interface RemoteEvent {
  /**
   * The stream is open. Events from before this point are lost, so everything shown has to be
   * refetched now. It is sent on every (re)connect.
   */
  data object Connected : RemoteEvent

  /** [status] is `queued`, `running`, `succeeded`, `failed` or `deleted`. */
  data class AnalysisChanged(val id: String, val recording: String, val status: String) :
      RemoteEvent

  /** [change] is `created`, `updated` or `deleted`. */
  data class RecordingChanged(val id: String, val change: String) : RemoteEvent
}

/** The address and the token a connection is made with. */
data class StreamTarget(val baseUrl: String, val token: String)

/**
 * One connection to the event stream. It emits [RemoteEvent.Connected] once the server has accepted
 * the stream and then the events, completes when the server ends the stream, and throws when the
 * connection fails: [TaigaUnauthorizedException] for a refused token, [TaigaNetworkException] when
 * the transport fails and [TaigaHttpException] for any other status.
 */
fun interface EventTransport {
  fun open(baseUrl: String, token: String): Flow<RemoteEvent>
}

/**
 * Reads the stream over OkHttp. Cancelling the collector cancels the call, which unblocks the read.
 * The timeout that fits a long-lived stream is the one [HttpClients.events] gives it; the call
 * itself shares the app's connection pool and dispatcher with everything else.
 */
class OkHttpEventTransport(
    private val client: OkHttpClient,
    private val userAgent: String = "taiga-sleep-android/${BuildConfig.VERSION_NAME}",
) : EventTransport {
  override fun open(baseUrl: String, token: String): Flow<RemoteEvent> = callbackFlow {
    val request =
        Request.Builder()
            .url(baseUrl.trimEnd('/') + EVENTS)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            // A compressing proxy would hold events back until its buffer fills.
            .header("Accept-Encoding", "identity")
            .header("Authorization", bearer(token))
            .header("User-Agent", userAgent)
            .build()
    val call = client.newCall(request)
    call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            this@callbackFlow.close(TaigaNetworkException(e))
          }

          override fun onResponse(call: Call, response: Response) {
            val failure =
                try {
                  response.use { this@callbackFlow.stream(it) }
                } catch (e: IOException) {
                  TaigaNetworkException(e)
                } catch (e: Exception) {
                  TaigaMalformedException(e)
                }
            this@callbackFlow.close(failure)
          }
        }
    )
    awaitClose { call.cancel() }
  }

  /** Reads one response to its end. Returns why it failed, or null when it ended cleanly. */
  private fun ProducerScope<RemoteEvent>.stream(response: Response): TaigaException? {
    if (!response.isSuccessful) {
      val status = response.code
      return if (status == 401 || status == 403) {
        TaigaUnauthorizedException(status, "HTTP $status")
      } else {
        TaigaHttpException(status, "HTTP $status")
      }
    }
    // A captive portal or a proxy answers 200 with a page; that is not the stream.
    val type = response.header("Content-Type").orEmpty()
    val source = response.body?.source()
    if (source == null || !type.startsWith("text/event-stream", ignoreCase = true)) {
      return TaigaMalformedException(IllegalStateException("not an event stream"))
    }
    if (trySendBlocking(RemoteEvent.Connected).isFailure) return null
    for (message in readMessages(source)) {
      val event = message.toEvent() ?: continue
      if (trySendBlocking(event).isFailure) return null
    }
    return null
  }

  private companion object {
    const val EVENTS = "/v1/events"
  }
}

/**
 * The server sends a comment every 20 seconds, so a stream that is silent for three times that is
 * dead, and the read gives up to let the connection be replaced.
 */
internal const val EVENT_READ_TIMEOUT_SECONDS = 60L

/**
 * An event stream that keeps itself connected. It reconnects with a growing pause (1 second,
 * doubling to 30) that starts over after a connection succeeded, and it stops when the collector
 * does. A refused token goes through [onUnauthorized], which refreshes or drops the session the way
 * the rest of the remote layer does; the stream carries on only while that says the session is
 * still usable.
 *
 * [target] is asked again for every connection, so a refreshed token is picked up. It returns null
 * when nothing is configured or nobody is logged in, and then the stream does not connect at all.
 */
class EventStream(
    private val target: () -> StreamTarget?,
    private val transport: EventTransport,
    private val onUnauthorized: suspend () -> Boolean,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
  fun events(): Flow<RemoteEvent> = flow {
    var pause = initialBackoffMs
    while (true) {
      val destination = target() ?: return@flow
      var failure: Throwable? = null
      transport
          .open(destination.baseUrl, destination.token)
          .catch { failure = it }
          .collect { event ->
            // The server answered: whatever went wrong before is over.
            if (event is RemoteEvent.Connected) pause = initialBackoffMs
            emit(event)
          }
      if (failure is TaigaUnauthorizedException && !sessionUsable()) return@flow
      sleep(pause)
      pause = (pause * 2).coerceAtMost(maxBackoffMs)
    }
  }

  private suspend fun sessionUsable(): Boolean =
      try {
        onUnauthorized()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        false
      }

  companion object {
    const val INITIAL_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 30_000L
  }
}

/**
 * Whether the event stream may run in this state: a session that works, or one whose server could
 * not be reached for a reason that can pass. A server that is not Taiga, speaks another API
 * revision or has a bad address is not something a reconnect fixes.
 */
fun RemoteStatus.canStream(): Boolean =
    when (this) {
      is RemoteStatus.Connected -> true
      is RemoteStatus.Unreachable ->
          reason == RemoteError.UnknownHost ||
              reason == RemoteError.Unreachable ||
              reason is RemoteError.Http
      is RemoteStatus.LoginNeeded,
      RemoteStatus.NotConfigured -> false
    }

/** One message of the stream: an event name and its data lines joined by newlines. */
internal data class SseMessage(val event: String, val data: String)

/**
 * The line protocol of Server-Sent Events. Lines end in LF or CRLF, which is what the server sends.
 * A comment (a line that starts with a colon, such as the keepalive) and the `retry` and `id`
 * fields change nothing: reconnecting is [EventStream]'s own policy, and nothing is replayed.
 */
internal class SseParser {
  private var event = ""
  private val data = StringBuilder()
  private var hasData = false
  private var first = true

  /** Feeds one line without its terminator. A blank line ends a message and returns it. */
  fun feed(raw: String): SseMessage? {
    var line = raw
    if (first) {
      first = false
      line = line.removePrefix("﻿")
    }
    if (line.isEmpty()) return dispatch()
    if (line.startsWith(':')) return null
    val colon = line.indexOf(':')
    val field = if (colon < 0) line else line.substring(0, colon)
    val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
    when (field) {
      "event" -> event = value
      "data" -> {
        if (hasData) data.append('\n')
        data.append(value)
        hasData = true
      }
    }
    return null
  }

  private fun dispatch(): SseMessage? {
    val message = if (hasData) SseMessage(event.ifEmpty { "message" }, data.toString()) else null
    event = ""
    data.setLength(0)
    hasData = false
    return message
  }
}

/** The messages of [source] until it ends. A read that fails throws; an overlong line ends it. */
internal fun readMessages(source: BufferedSource): Sequence<SseMessage> = sequence {
  val parser = SseParser()
  while (true) {
    val line =
        try {
          source.readUtf8LineStrict(MAX_LINE_BYTES)
        } catch (_: EOFException) {
          break
        }
    parser.feed(line)?.let { yield(it) }
  }
}

private const val MAX_LINE_BYTES = 64L * 1024

private val eventJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class AnalysisEventDto(
    val id: String = "",
    val recording: String = "",
    val status: String = "",
)

@Serializable private data class RecordingEventDto(val id: String = "", val change: String = "")

/** The event a message stands for. Unknown names and data that cannot be read are ignored. */
internal fun SseMessage.toEvent(): RemoteEvent? =
    try {
      when (event) {
        "analysis" ->
            eventJson.decodeFromString<AnalysisEventDto>(data).let {
              if (it.id.isBlank() || it.recording.isBlank()) null
              else RemoteEvent.AnalysisChanged(it.id, it.recording, it.status)
            }
        "recording" ->
            eventJson.decodeFromString<RecordingEventDto>(data).let {
              if (it.id.isBlank()) null else RemoteEvent.RecordingChanged(it.id, it.change)
            }
        else -> null
      }
    } catch (_: IllegalArgumentException) {
      // Includes SerializationException. Nothing is lost: the next connect refetches.
      null
    }
