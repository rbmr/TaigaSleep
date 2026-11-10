package com.rbmr.taigasleep.backend

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The HTTP clients of the app. All three are derived from one [base] client with
 * [OkHttpClient.newBuilder], so they keep its connection pool and its dispatcher: the different
 * timeouts the remote checks, the API and the event stream need do not cost three pools. The app
 * container owns the one instance.
 */
class HttpClients(base: OkHttpClient) {
  /** The address and login checks: a server that does not answer is reported quickly. */
  val remote: OkHttpClient =
      base.newBuilder()
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .callTimeout(30, TimeUnit.SECONDS)
          .build()

  /** The ordinary authenticated calls, uploads included. */
  val api: OkHttpClient =
      base.newBuilder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(90, TimeUnit.SECONDS)
          .writeTimeout(90, TimeUnit.SECONDS)
          .callTimeout(5, TimeUnit.MINUTES)
          .retryOnConnectionFailure(true)
          .build()

  /**
   * The event stream, which is meant to stay open: the call has no deadline of its own, and the
   * read gives up after [EVENT_READ_TIMEOUT_SECONDS] of silence, three times the server's
   * keepalive.
   */
  val events: OkHttpClient =
      base.newBuilder()
          .connectTimeout(15, TimeUnit.SECONDS)
          .readTimeout(EVENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .callTimeout(0, TimeUnit.SECONDS)
          .build()
}
