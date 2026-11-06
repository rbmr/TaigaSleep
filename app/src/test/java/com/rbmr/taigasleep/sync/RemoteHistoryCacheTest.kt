package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteHistoryCacheTest {
  private lateinit var root: File
  private lateinit var cache: RemoteHistoryCache

  @Before
  fun setUp() {
    root = Files.createTempDirectory("taiga-history").toFile()
    cache = RemoteHistoryCache(File(root, "history/remote-recordings.json"))
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
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
    cache.save("https://sleep.example|me@example.com", list)

    val loaded = cache.load("https://sleep.example|me@example.com")

    assertEquals("20261023_223000-k3f9", loaded?.items?.single()?.recording?.id)
    assertEquals("a1", loaded?.items?.single()?.analyses?.single()?.id)
  }

  @Test
  fun anotherAccountNeverSeesIt() {
    cache.save("https://sleep.example|me@example.com", list)

    assertNull(cache.load("https://sleep.example|you@example.com"))
    assertNull(cache.load(""))
  }

  @Test
  fun aFileThatCannotBeReadIsIgnored() {
    File(root, "history").mkdirs()
    File(root, "history/remote-recordings.json").writeText("not json")

    assertNull(cache.load("https://sleep.example|me@example.com"))
  }

  @Test
  fun `a completed delete removes the recording from the saved list`() {
    cache.save(account, twoRecordings())

    cache.remove(account, "20261023_223000-k3f9")

    val saved = cache.load(account)
    assertEquals(listOf("20261024_223000-m1a2"), saved?.items?.map { it.recording.id })
    assertTrue("20261023_223000-k3f9" in cache.removed.value)
  }

  @Test
  fun `a 404 on a remote-only recording removes it from the list`() {
    // This is the call the library makes for a 404, including for an entry that is only in memory.
    cache.remove(account, "20261023_223000-k3f9")

    val fetchedEarlier = twoRecordings()
    val shown = fetchedEarlier.withoutRecordings(cache.removed.value)

    assertEquals(listOf("20261024_223000-m1a2"), shown.items.map { it.recording.id })
  }

  @Test
  fun `removing the same recording twice changes nothing`() {
    cache.save(account, twoRecordings())

    cache.remove(account, "20261023_223000-k3f9")
    cache.remove(account, "20261023_223000-k3f9")

    assertEquals(1, cache.load(account)?.items?.size)
  }

  @Test
  fun `a recording that is still on the phone can return after it is uploaded again`() {
    cache.save(account, twoRecordings())

    cache.remove(account, "20261023_223000-k3f9", keepOut = false)

    assertEquals(1, cache.load(account)?.items?.size)
    assertTrue(cache.removed.value.isEmpty())
  }

  @Test
  fun `the run a night shows comes back with the list`() {
    cache.save(account, twoRecordings())

    cache.select(account, "20261023_223000-k3f9", "a1")

    assertEquals(mapOf("20261023_223000-k3f9" to "a1"), cache.selected(account))
    // A later fetch of the list keeps the choice.
    cache.save(account, twoRecordings())
    assertEquals("a1", cache.selected(account)["20261023_223000-k3f9"])
  }

  @Test
  fun `another account never sees the selection`() {
    cache.save(account, twoRecordings())
    cache.select(account, "20261023_223000-k3f9", "a1")

    assertTrue(cache.selected("https://sleep.example|you@example.com").isEmpty())
    assertTrue(cache.selected("").isEmpty())
  }

  @Test
  fun `the choice leaves when the recording is deleted`() {
    cache.save(account, twoRecordings())
    cache.select(account, "20261023_223000-k3f9", "a1")
    cache.select(account, "remote-20261023_223000-k3f9", "a1")

    cache.remove(account, "20261023_223000-k3f9")

    assertTrue(cache.selected(account).isEmpty())
  }

  @Test
  fun `a file written before the selection was kept still loads`() {
    File(root, "history").mkdirs()
    File(root, "history/remote-recordings.json")
        .writeText("""{"accountKey":"$account","recordings":{"items":[]}}""")

    assertEquals(0, cache.load(account)?.items?.size)
    assertTrue(cache.selected(account).isEmpty())
  }

  private val account = "https://sleep.example|me@example.com"

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
