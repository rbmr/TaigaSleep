package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import java.io.EOFException
import java.io.IOException
import java.net.URLEncoder
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

/**
 * What PocketBase's realtime stream tells the app (docs/api.md, `x-realtime`). A change carries the
 * record's id and status, never the payload: whoever shows something refetches it.
 */
sealed interface RemoteEvent {
  /**
   * The stream is subscribed. Changes from before this point are lost, so everything shown has to
   * be refetched now. It is sent on every (re)connect.
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
 * One connection to the realtime stream. It emits [RemoteEvent.Connected] once the subscription is
 * made and then the changes, completes when the server ends the stream, and throws when the
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
            .url(baseUrl.trimEnd('/') + REALTIME)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            // A compressing proxy would hold changes back until its buffer fills.
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
            val failure: Throwable? =
                try {
                  response.use { this@callbackFlow.read(it, baseUrl, token) }
                } catch (e: IOException) {
                  TaigaNetworkException(e)
                } catch (e: CancellationException) {
                  // The collector went away: the stream ends for that reason, not as a bad answer.
                  e
                } catch (e: TaigaException) {
                  e
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
  private fun ProducerScope<RemoteEvent>.read(
      response: Response,
      baseUrl: String,
      token: String,
  ): TaigaException? {
    if (!response.isSuccessful) return failureFor(response.code)
    // A captive portal or a proxy answers 200 with a page; that is not the stream.
    val type = response.header("Content-Type").orEmpty()
    val source = response.body?.source()
    if (source == null || !type.startsWith("text/event-stream", ignoreCase = true)) {
      return TaigaMalformedException(IllegalStateException("not a realtime stream"))
    }
    var subscribed = false
    for (message in readMessages(source)) {
      val clientId = message.clientId()
      if (clientId != null) {
        // PocketBase names the connection first; the changes start only after this subscription.
        subscribe(baseUrl, token, clientId)?.let {
          return it
        }
        if (!subscribed) {
          subscribed = true
          if (trySendBlocking(RemoteEvent.Connected).isFailure) return null
        }
        continue
      }
      val event = message.toEvent() ?: continue
      if (trySendBlocking(event).isFailure) return null
    }
    return null
  }

  /**
   * Subscribes the named connection to the topics the app shows. It is a synchronous call: the
   * stream has nothing to deliver until it is made. Returns why it failed, or null.
   */
  private fun subscribe(baseUrl: String, token: String, clientId: String): TaigaException? =
      try {
        val payload =
            buildJsonObject {
                  put("clientId", clientId)
                  put("subscriptions", buildJsonArray { TOPICS.forEach { add(it) } })
                }
                .toString()
        val request =
            Request.Builder()
                .url(baseUrl.trimEnd('/') + REALTIME)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Authorization", bearer(token))
                .header("User-Agent", userAgent)
                .build()
        client.newCall(request).execute().use { response ->
          if (response.isSuccessful) null else failureFor(response.code)
        }
      } catch (e: IOException) {
        TaigaNetworkException(e)
      }

  private fun failureFor(status: Int): TaigaException =
      if (status == 401 || status == 403) {
        TaigaUnauthorizedException(status, "HTTP $status")
      } else {
        TaigaHttpException(status, "HTTP $status")
      }

  private companion object {
    const val REALTIME = "/api/realtime"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * The changes the app shows: its recordings, and its analyses with just enough of the record to
     * refresh the recording it belongs to. Only these fields are delivered, so a result never
     * travels twice.
     */
    val TOPICS: List<String> =
        listOf(topic("recordings", "id"), topic("analyses", "id,recording,status"))

    fun topic(collection: String, fields: String): String {
      val options =
          buildJsonObject { put("query", buildJsonObject { put("fields", fields) }) }.toString()
      return "$collection/*?options=" + URLEncoder.encode(options, "UTF-8")
    }
  }
}

/**
 * PocketBase closes an idle connection after five minutes and every connection after thirty, so a
 * stream that is silent for longer than that is dead, and the read gives up to let the connection be
 * replaced.
 */
internal const val REALTIME_READ_TIMEOUT_SECONDS = 35L * 60

/**
 * A realtime stream that keeps itself connected. It reconnects with a growing pause (1 second,
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
 * Whether the realtime stream may run in this state: a session that works, or one whose server could
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
 * A comment (a line that starts with a colon) and the `id` and `retry` fields change nothing:
 * reconnecting is [EventStream]'s own policy, and nothing is replayed.
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

/**
 * The messages of [source] until it ends. A read that fails throws; an overlong line ends it. The
 * limit is far above the small field selection the app asks for, and only bounds what a broken
 * server can make the app buffer.
 */
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

private const val MAX_LINE_BYTES = 6L * 1024 * 1024

private val eventJson = Json { ignoreUnknownKeys = true }

@Serializable private data class RealtimeConnectDto(val clientId: String = "")

/**
 * One change of a record: `action` is `create`, `update` or `delete`, and `record` carries the
 * fields the subscription asked for.
 */
@Serializable
private data class RealtimeChangeDto(
    val action: String = "",
    val record: RealtimeRecordDto? = null,
)

@Serializable
private data class RealtimeRecordDto(
    val id: String = "",
    val recording: String = "",
    val status: String = "",
)

/** The id a `PB_CONNECT` message names, or null for any other message. */
internal fun SseMessage.clientId(): String? {
  if (event != "PB_CONNECT") return null
  return try {
    eventJson.decodeFromString<RealtimeConnectDto>(data).clientId.ifBlank { null }
  } catch (_: IllegalArgumentException) {
    // Includes SerializationException. Without a client id nothing can be subscribed.
    null
  }
}

/**
 * The change a message stands for. The topic is the subscription the app made, so the collection
 * prefix is what names it; messages from another collection and unreadable data are ignored.
 */
internal fun SseMessage.toEvent(): RemoteEvent? =
    try {
      when {
        event.startsWith("analyses/") -> {
          val change = eventJson.decodeFromString<RealtimeChangeDto>(data)
          val record = change.record
          when {
            record == null || record.id.isBlank() || record.recording.isBlank() -> null
            change.action == "delete" ->
                RemoteEvent.AnalysisChanged(record.id, record.recording, "deleted")
            else -> RemoteEvent.AnalysisChanged(record.id, record.recording, record.status)
          }
        }
        event.startsWith("recordings/") -> {
          val change = eventJson.decodeFromString<RealtimeChangeDto>(data)
          val id = change.record?.id.orEmpty()
          when {
            id.isBlank() -> null
            change.action == "delete" -> RemoteEvent.RecordingChanged(id, "deleted")
            change.action == "create" -> RemoteEvent.RecordingChanged(id, "created")
            else -> RemoteEvent.RecordingChanged(id, "updated")
          }
        }
        else -> null
      }
    } catch (_: IllegalArgumentException) {
      // Includes SerializationException. Nothing is lost: the next connect refetches.
      null
    }
