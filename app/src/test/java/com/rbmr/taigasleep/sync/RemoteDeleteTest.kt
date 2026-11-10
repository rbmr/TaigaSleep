package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"

/** A client whose delete answers what the test says and remembers who was asked. */
private class DeletingClient(private val failure: Exception? = null) : StubTaigaClient() {
  val deleted = mutableListOf<String>()

  override suspend fun deleteRecording(id: String) {
    deleted += id
    failure?.let { throw it }
  }

  override suspend fun getRecording(id: String): RecordingDto = RecordingDto(id = id)
}

/**
 * The rule that makes a queued delete safe: it runs for the account that queued it, and only while
 * that account is the active one.
 */
class RemoteDeleteTest {
  private val completed = mutableListOf<String>()

  private fun run(
      queuedFor: String?,
      active: String?,
      client: DeletingClient,
      verify: Boolean = true,
  ) = runBlocking {
    RemoteDelete.run(
        recordingId = "r1",
        queuedFor = queuedFor,
        activeAccount = { active },
        verify = { verify },
        api = { client },
        onDeleted = { completed += it },
    )
  }

  @Test
  fun `a delete queued for one account is never sent to another`() {
    val client = DeletingClient()

    val outcome = run(queuedFor = A, active = B, client = client)

    assertEquals(RemoteDelete.Outcome.OTHER_ACCOUNT, outcome)
    assertTrue("the delete reached ${client.deleted}", client.deleted.isEmpty())
    assertTrue(completed.isEmpty())
  }

  @Test
  fun `a delete runs for the account that queued it`() {
    val client = DeletingClient()

    val outcome = run(queuedFor = A, active = A, client = client)

    assertEquals(RemoteDelete.Outcome.COMPLETE, outcome)
    assertEquals(listOf("r1"), client.deleted)
    assertEquals(listOf(A), completed)
  }

  @Test
  fun `a 404 completes the delete of that same account`() {
    val client = DeletingClient(TaigaHttpException(404, "recording not found", "not_found"))

    val outcome = run(queuedFor = A, active = A, client = client)

    assertEquals(RemoteDelete.Outcome.COMPLETE, outcome)
    assertEquals(listOf(A), completed)
  }

  @Test
  fun `a network failure keeps the delete queued`() {
    val client = DeletingClient(TaigaNetworkException(IOException("offline")))

    val outcome = run(queuedFor = A, active = A, client = client)

    assertEquals(RemoteDelete.Outcome.DEFER, outcome)
    assertTrue(completed.isEmpty())
  }

  @Test
  fun `a failed session check keeps the delete queued`() {
    val client = DeletingClient()

    val outcome = run(queuedFor = A, active = A, client = client, verify = false)

    assertEquals(RemoteDelete.Outcome.DEFER, outcome)
    assertTrue(client.deleted.isEmpty())
  }

  @Test
  fun `a delete queued before accounts existed runs for the account that is active`() {
    val client = DeletingClient()

    val outcome = run(queuedFor = null, active = B, client = client)

    assertEquals(RemoteDelete.Outcome.COMPLETE, outcome)
    assertEquals(listOf(B), completed)
  }
}
