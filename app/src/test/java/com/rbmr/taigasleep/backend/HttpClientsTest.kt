package com.rbmr.taigasleep.backend

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The per-server clients may differ in timeouts, but they must not create pools of their own. */
class HttpClientsTest {
  @Test
  fun `every derived client shares the pool and the dispatcher of the base client`() {
    val base = OkHttpClient()
    val clients = HttpClients(base)

    for (client in listOf(clients.remote, clients.api, clients.events)) {
      assertSame(base.connectionPool, client.connectionPool)
      assertSame(base.dispatcher, client.dispatcher)
    }
  }

  @Test
  fun `the timeouts of the old clients are kept`() {
    val clients = HttpClients(OkHttpClient())

    assertEquals(20_000, clients.remote.readTimeoutMillis)
    assertEquals(90_000, clients.api.readTimeoutMillis)
    assertEquals(300_000, clients.api.callTimeoutMillis)
    assertEquals(2_100_000, clients.events.readTimeoutMillis)
    assertEquals(0, clients.events.callTimeoutMillis)
  }
}
