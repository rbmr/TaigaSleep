package com.rbmr.taigasleep.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlTest {
  private fun parsed(raw: String): RemoteAddress =
      RemoteUrl.parse(raw).getOrElse { throw AssertionError("expected $raw to parse", it) }

  private fun error(raw: String): RemoteError =
      (RemoteUrl.parse(raw).exceptionOrNull() as RemoteUrlException).error

  @Test
  fun `normalizes host, port and trailing slash`() {
    assertEquals("https://sleep.example.com", parsed("https://Sleep.Example.com/").baseUrl)
    assertEquals("http://192.168.1.5:8090", parsed("http://192.168.1.5:8090/").baseUrl)
    assertEquals("http://192.168.1.5:8090", parsed("192.168.1.5:8090").baseUrl)
    assertEquals("http://[::1]:8090", parsed("http://[::1]:8090").baseUrl)
  }

  @Test
  fun `keeps a path prefix for a reverse proxy`() {
    assertEquals(
        "https://example.com/taiga",
        parsed("https://example.com/taiga/").baseUrl,
    )
  }

  @Test
  fun `adds http for private addresses and https for public ones`() {
    assertTrue(parsed("nas:8090").insecure)
    assertTrue(parsed("localhost:8090").insecure)
    assertTrue(parsed("100.101.102.103:8090").insecure)
    assertFalse(parsed("sleep.example.com").insecure)
  }

  @Test
  fun `accepts http on private networks`() {
    listOf(
            "http://localhost:8090",
            "http://127.0.0.1:8090",
            "http://10.1.2.3",
            "http://172.16.5.4",
            "http://172.31.255.1",
            "http://192.168.0.10",
            "http://169.254.1.1",
            "http://100.64.0.1",
            "http://[::1]:8090",
            "http://nas.local",
            "http://sleep.home.arpa",
            "http://server.ts.net",
            "http://nas",
        )
        .forEach { raw ->
          assertTrue("$raw should be allowed", RemoteUrl.parse(raw).isSuccess)
        }
  }

  @Test
  fun `rejects http on a public address`() {
    assertEquals(RemoteError.InsecureUrl, error("http://sleep.example.com"))
    assertEquals(RemoteError.InsecureUrl, error("http://172.32.0.1"))
  }

  @Test
  fun `rejects malformed and unsupported addresses`() {
    assertEquals(RemoteError.InvalidUrl, error(""))
    assertEquals(RemoteError.InvalidUrl, error("   "))
    assertEquals(RemoteError.InvalidUrl, error("ftp://example.com"))
    assertEquals(RemoteError.InvalidUrl, error("https://user:pass@example.com"))
    assertEquals(RemoteError.InvalidUrl, error("https://example.com?token=1"))
  }

  @Test
  fun `rejects https without a host`() {
    assertEquals(RemoteError.InvalidUrl, error("https://"))
    assertEquals(RemoteError.InvalidUrl, error("https:///path"))
  }
}
