package com.rbmr.taigasleep.ui.settings

import com.rbmr.taigasleep.backend.AccessTokenDto
import com.rbmr.taigasleep.backend.AccessTokenListDto
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TokenLifetime
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The token API of the server, in memory; a test sets the failure it needs. */
private class FakeTokenClient : StubTaigaClient() {
  val tokens = mutableListOf<AccessTokenDto>()
  var listFailure: Exception? = null
  var createFailure: Exception? = null
  var revokeFailure: Exception? = null
  var creations = 0
  val lifetimes = mutableListOf<TokenLifetime>()
  var revoked = mutableListOf<String>()

  override suspend fun listAccessTokens(): AccessTokenListDto {
    listFailure?.let { throw it }
    return AccessTokenListDto(tokens.toList())
  }

  override suspend fun createAccessToken(name: String, lifetime: TokenLifetime): AccessTokenDto {
    createFailure?.let { throw it }
    creations++
    lifetimes += lifetime
    val created =
        AccessTokenDto(
            id = "t$creations",
            name = name,
            hint = "wxyz",
            token = "ts_secret$creations",
            expires_at = if (lifetime == TokenLifetime.NEVER) null else "2027-01-10T20:00:00Z",
        )
    tokens += created
    return created
  }

  override suspend fun deleteAccessToken(id: String) {
    revokeFailure?.let { throw it }
    revoked += id
    tokens.removeAll { it.id == id }
  }
}

class AccessTokensViewModelTest {
  private val client = FakeTokenClient()

  private fun viewModel(signedIn: Boolean = true): AccessTokensViewModel =
      AccessTokensViewModel(
          client = { if (signedIn) client else null },
          dispatcher = Dispatchers.Unconfined,
      )

  private fun token(
      id: String,
      name: String,
      expiresAt: String? = null,
      expired: Boolean = false,
  ) =
      AccessTokenDto(
          id = id,
          name = name,
          hint = "abcd",
          created = "2026-11-10T20:00:00Z",
          expires_at = expiresAt,
          expired = expired,
      )

  @Test
  fun `refreshing shows the account's tokens`() {
    client.tokens += token("t1", "laptop")

    val model = viewModel()
    model.refresh()

    assertEquals(listOf("laptop"), model.state.value.tokens.map { it.name })
    assertFalse(model.state.value.loading)
    assertTrue(model.state.value.signedIn)
  }

  @Test
  fun `a list never holds the token itself`() {
    client.tokens += token("t1", "laptop").copy(token = null)

    val model = viewModel()
    model.refresh()

    assertNull(model.state.value.tokens.single().token)
  }

  @Test
  fun `creating a token adds it and holds the secret once`() {
    val model = viewModel()

    model.create("  my assistant ", AccessTokenLifetime.NEVER)

    assertEquals(listOf("my assistant"), model.state.value.tokens.map { it.name })
    assertEquals("ts_secret1", model.state.value.created)
    assertNull(model.state.value.tokens.single().token)
    assertEquals(1, client.creations)
  }

  @Test
  fun `a chosen lifetime goes to the server and its expiry comes back with the token`() {
    val model = viewModel()

    model.create("my assistant", AccessTokenLifetime.DAYS_30)

    assertEquals(listOf(TokenLifetime.DAYS_30), client.lifetimes)
    val shown = model.state.value.tokens.single()
    assertEquals("2027-01-10T20:00:00Z", shown.expires_at)
    assertFalse(shown.expired)
  }

  @Test
  fun `a token without a lifetime has no expiry`() {
    val model = viewModel()

    model.create("my assistant", AccessTokenLifetime.NEVER)

    assertNull(model.state.value.tokens.single().expires_at)
  }

  @Test
  fun `the list shows a token the server refuses as expired`() {
    client.tokens +=
        token("t1", "old script", expiresAt = "2026-12-10T20:00:00Z", expired = true)

    val model = viewModel()
    model.refresh()

    val shown = model.state.value.tokens.single()
    assertEquals("2026-12-10T20:00:00Z", shown.expires_at)
    assertTrue(shown.expired)
  }

  @Test
  fun `the new token is at the top of the list`() {
    client.tokens += token("old", "old laptop")
    val model = viewModel()
    model.refresh()

    model.create("new assistant", AccessTokenLifetime.NEVER)

    assertEquals(listOf("new assistant", "old laptop"), model.state.value.tokens.map { it.name })
  }

  @Test
  fun `dismissing the new token stops the page from holding it`() {
    val model = viewModel()
    model.create("my assistant", AccessTokenLifetime.NEVER)

    model.dismissCreated()

    assertNull(model.state.value.created)
  }

  @Test
  fun `a blank name creates nothing`() {
    val model = viewModel()

    model.create("   ", AccessTokenLifetime.NEVER)

    assertEquals(0, client.creations)
    assertTrue(model.state.value.tokens.isEmpty())
  }

  @Test
  fun `a failed create keeps the list and reports the error`() {
    client.tokens += token("t1", "laptop")
    val model = viewModel()
    model.refresh()
    client.createFailure = IllegalStateException("the server refused it")

    model.create("my assistant", AccessTokenLifetime.NEVER)

    assertEquals("the server refused it", model.state.value.error)
    assertEquals(listOf("laptop"), model.state.value.tokens.map { it.name })
    assertNull(model.state.value.created)
    assertFalse(model.state.value.busy)
  }

  @Test
  fun `a failed list keeps nothing and reports the error`() {
    client.listFailure = IllegalStateException("network unavailable")

    val model = viewModel()
    model.refresh()

    assertEquals("network unavailable", model.state.value.error)
    assertFalse(model.state.value.loading)
  }

  @Test
  fun `revoking removes the token once the server accepted it`() {
    client.tokens += token("t1", "laptop")
    client.tokens += token("t2", "tablet")
    val model = viewModel()
    model.refresh()

    model.revoke("t1")

    assertEquals(listOf("tablet"), model.state.value.tokens.map { it.name })
    assertEquals(listOf("t1"), client.revoked)
  }

  @Test
  fun `a failed revoke keeps the row and reports the error`() {
    client.tokens += token("t1", "laptop")
    val model = viewModel()
    model.refresh()
    client.revokeFailure = IllegalStateException("not found")

    model.revoke("t1")

    assertEquals("not found", model.state.value.error)
    assertEquals(listOf("laptop"), model.state.value.tokens.map { it.name })
    assertFalse(model.state.value.busy)
  }

  @Test
  fun `without a signed-in account there is nothing to list`() {
    val model = viewModel(signedIn = false)

    model.refresh()

    assertFalse(model.state.value.signedIn)
    assertTrue(model.state.value.tokens.isEmpty())
    assertFalse(model.state.value.loading)
  }
}
