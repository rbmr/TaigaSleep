package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.testDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The server's list of every account and the run each recording shows, kept in the app's tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteHistoryCacheTest {
  private lateinit var database: TaigaDatabase
  private lateinit var cache: RemoteHistoryCache

  @Before
  fun setUp() {
    database = testDatabase()
    cache = RemoteHistoryCache(database.remoteRecordings())
  }

  @After
  fun tearDown() {
    database.close()
  }

  private val list =
      RecordingSummaryListDto(
          listOf(
              RecordingSummaryDto(
                  recording = RecordingDto(id = "20261023_223000-k3f9", complete = true),
                  analyses = listOf(AnalysisDto(id = "a1", status = "succeeded")),
              )
          )
      )

  @Test
  fun theListComesBackForTheAccountThatSavedIt() {
    runBlocking { cache.save("https://sleep.example|me@example.com", list) }

    val loaded = runBlocking { cache.load("https://sleep.example|me@example.com") }

    assertEquals("20261023_223000-k3f9", loaded?.items?.single()?.recording?.id)
    assertEquals("a1", loaded?.items?.single()?.analyses?.single()?.id)
  }

  @Test
  fun anotherAccountNeverSeesIt() {
    runBlocking { cache.save("https://sleep.example|me@example.com", list) }

    assertNull(runBlocking { cache.load("https://sleep.example|you@example.com") })
    assertNull(runBlocking { cache.load("") })
  }

  @Test
  fun `an account that never saved a list has none`() {
    assertNull(runBlocking { cache.load("https://sleep.example|me@example.com") })
    assertTrue(runBlocking { cache.selected("https://sleep.example|me@example.com") }.isEmpty())
  }

  @Test
  fun `a completed delete removes the recording from the saved list`() {
    runBlocking { cache.save(account, twoRecordings()) }

    runBlocking { cache.remove(account, "20261023_223000-k3f9") }

    val saved = runBlocking { cache.load(account) }
    assertEquals(listOf("20261024_223000-m1a2"), saved?.items?.map { it.recording.id })
    assertTrue("20261023_223000-k3f9" in runBlocking { cache.removed(account) })
  }

  @Test
  fun `a 404 on a remote-only recording removes it from the list`() {
    // This is the call the library makes for a 404, including for an entry that is only in memory.
    runBlocking { cache.remove(account, "20261023_223000-k3f9") }

    val fetchedEarlier = twoRecordings()
    val shown = fetchedEarlier.withoutRecordings(runBlocking { cache.removed(account) })

    assertEquals(listOf("20261024_223000-m1a2"), shown.items.map { it.recording.id })
  }

  @Test
  fun `removing the same recording twice changes nothing`() {
    runBlocking {
      cache.save(account, twoRecordings())

      cache.remove(account, "20261023_223000-k3f9")
      cache.remove(account, "20261023_223000-k3f9")
    }

    assertEquals(1, runBlocking { cache.load(account) }?.items?.size)
  }

  @Test
  fun `a recording that is still on the phone can return after it is uploaded again`() {
    runBlocking {
      cache.save(account, twoRecordings())

      cache.remove(account, "20261023_223000-k3f9", keepOut = false)
    }

    assertEquals(1, runBlocking { cache.load(account) }?.items?.size)
    assertTrue(runBlocking { cache.removed(account) }.isEmpty())
  }

  @Test
  fun `the run a night shows comes back with the list`() {
    runBlocking {
      cache.save(account, twoRecordings())

      cache.select(account, "20261023_223000-k3f9", "a1")
    }

    assertEquals(
        mapOf("20261023_223000-k3f9" to "a1"),
        runBlocking { cache.selected(account) },
    )
    // A later fetch of the list keeps the choice.
    runBlocking { cache.save(account, twoRecordings()) }
    assertEquals("a1", runBlocking { cache.selected(account) }["20261023_223000-k3f9"])
  }

  @Test
  fun `another account never sees the selection`() {
    runBlocking {
      cache.save(account, twoRecordings())
      cache.select(account, "20261023_223000-k3f9", "a1")
    }

    assertTrue(runBlocking { cache.selected("https://sleep.example|you@example.com") }.isEmpty())
    assertTrue(runBlocking { cache.selected("") }.isEmpty())
  }

  @Test
  fun `the choice leaves when the recording is deleted`() {
    runBlocking {
      cache.save(account, twoRecordings())
      cache.select(account, "20261023_223000-k3f9", "a1")
      cache.select(account, "remote-20261023_223000-k3f9", "a1")

      cache.remove(account, "20261023_223000-k3f9")
    }

    assertTrue(runBlocking { cache.selected(account) }.isEmpty())
  }

  private val account = "https://sleep.example|me@example.com"
  private val other = "https://sleep.example|you@example.com"

  @Test
  fun `each account keeps its own list`() {
    runBlocking {
      cache.save(account, twoRecordings())
      cache.save(other, list)
    }

    assertEquals(2, runBlocking { cache.load(account) }?.items?.size)
    assertEquals(1, runBlocking { cache.load(other) }?.items?.size)
  }

  @Test
  fun `a completed delete hides the night for that account only`() {
    runBlocking {
      cache.save(account, twoRecordings())
      cache.save(other, twoRecordings())

      cache.remove(account, "20261023_223000-k3f9")
    }
    assertTrue(
        runBlocking { cache.load(other) }?.items?.any { it.recording.id == "20261023_223000-k3f9" } ==
            true
    )
    assertTrue(runBlocking { cache.removed(other) }.isEmpty())

    assertTrue("20261023_223000-k3f9" in runBlocking { cache.removed(account) })
  }

  private fun twoRecordings() =
      RecordingSummaryListDto(
          listOf(
              RecordingSummaryDto(
                  recording = RecordingDto(id = "20261023_223000-k3f9", complete = true),
                  analyses = listOf(AnalysisDto(id = "a1", status = "succeeded")),
              ),
              RecordingSummaryDto(
                  recording = RecordingDto(id = "20261024_223000-m1a2", complete = true),
              ),
          )
      )
}
