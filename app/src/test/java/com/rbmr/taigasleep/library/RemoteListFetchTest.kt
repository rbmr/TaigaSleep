package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AnalysisListDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingListDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.StubTaigaClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"

private class ListingClient : StubTaigaClient() {
  override suspend fun listRecordings(limit: Int): RecordingListDto =
      RecordingListDto(items = listOf(RecordingDto(id = "r1", complete = true)))

  override suspend fun listAnalyses(recording: String?, limit: Int, offset: Int): AnalysisListDto =
      AnalysisListDto()
}

/**
 * A list fetched for one account is never stored under another: the account is read with the client
 * at the start and checked again before anything is saved.
 */
class RemoteListFetchTest {
  private val saved = mutableListOf<Pair<String, List<String>>>()

  private fun fetch(active: () -> String?, session: String = A) =
      runBlocking {
        fetchRemoteList(
            session = RemoteSession(session, ListingClient()),
            activeAccount = active,
            hidden = { emptySet() },
            save = { account, list -> saved += account to list.items.map { it.recording.id } },
        )
      }

  @Test
  fun `a list fetched for the active account is saved for it`() {
    val list = fetch(active = { A })

    assertEquals(listOf("r1"), list?.items?.map { it.recording.id })
    assertEquals(listOf(A to listOf("r1")), saved)
  }

  @Test
  fun `a list fetched for one account during a switch is dropped`() {
    val list = fetch(active = { B })

    assertNull(list)
    assertTrue("the list was saved for ${saved.map { it.first }}", saved.isEmpty())
  }

  @Test
  fun `a list fetched while logging out is dropped`() {
    val list = fetch(active = { null })

    assertNull(list)
    assertTrue(saved.isEmpty())
  }
}
