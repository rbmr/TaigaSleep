package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.StageTimelineDto
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.testDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ACCOUNT = "https://sleep.example|u1"

/** The downloaded payloads of every account, kept in the app's tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultCacheTest {
  private lateinit var database: TaigaDatabase
  private lateinit var cache: ResultCache

  @Before
  fun setUp() {
    database = testDatabase()
    cache = ResultCache(database.analysisResults())
  }

  @After
  fun tearDown() {
    database.close()
  }

  private fun analysis(id: String, score: Double = 80.0) =
      AnalysisDto(
          id = id,
          recording = "r1",
          algorithm = "wav2sleep",
          version = "0.1.0",
          status = "succeeded",
          result =
              AnalysisResultDto(
                  contract_version = 3,
                  model = "wav2sleep",
                  model_version = "0.1.0",
                  stages = StageTimelineDto(count = 2, segments = listOf(StageSegmentDto("light", 2))),
                  items =
                      listOf(
                          ResultItemDto(
                              kind = "score",
                              id = "sleep_quality",
                              label = "Sleep score",
                              group = "score",
                              unit = "%",
                              value = score,
                          )
                      ),
              ),
      )

  @Test
  fun aSavedAnalysisComesBackWithItsResult() {
    assertTrue(runBlocking { cache.save(ACCOUNT, "night", "a1", analysis("a1")) })

    val loaded = runBlocking { cache.load(ACCOUNT, "night", "a1") }

    assertEquals("a1", loaded?.id)
    assertEquals("wav2sleep", loaded?.algorithm)
    assertEquals(80.0, loaded?.result?.items?.single()?.value ?: 0.0, 0.001)
    assertEquals("light", loaded?.result?.stages?.segments?.single()?.stage)
    assertTrue(runBlocking { cache.has(ACCOUNT, "night", "a1") })
  }

  @Test
  fun runsOfTheSameNightDoNotReplaceEachOther() {
    runBlocking {
      cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))
      cache.save(ACCOUNT, "night", "a2", analysis("a2", score = 90.0))
    }

    assertEquals(
        70.0,
        runBlocking { cache.load(ACCOUNT, "night", "a1") }?.result?.items?.single()?.value ?: 0.0,
        0.001,
    )
    assertEquals(
        90.0,
        runBlocking { cache.load(ACCOUNT, "night", "a2") }?.result?.items?.single()?.value ?: 0.0,
        0.001,
    )
    assertFalse(runBlocking { cache.has(ACCOUNT, "night", "a3") })
  }

  @Test
  fun `a night's single payload only answers its own run`() {
    // The one payload an older version kept per recording is now the row of that run.
    val run = AnalysisDto(id = "a1", status = "succeeded")
    runBlocking { cache.save(ACCOUNT, "night", run) }

    assertEquals("a1", runBlocking { cache.load(ACCOUNT, "night", "a1") }?.id)
    assertNull(runBlocking { cache.load(ACCOUNT, "night", "other") })
  }

  @Test
  fun deletingANightRemovesEveryRunOfIt() {
    runBlocking {
      cache.save(ACCOUNT, "night", "a1", analysis("a1"))
      cache.save("night", "a2", analysis("a2"))
      cache.save(ACCOUNT, "other", "a3", analysis("a3"))

      cache.delete(ACCOUNT, "night")
    }

    assertNull(runBlocking { cache.load(ACCOUNT, "night", "a1") })
    assertNull(runBlocking { cache.load(ACCOUNT, "night", "a2") })
    assertNotNull(runBlocking { cache.load(ACCOUNT, "other", "a3") })
    assertTrue(runBlocking { cache.cached(ACCOUNT, "night") }.isEmpty())
  }

  @Test
  fun everyCachedRunOfNightComesBackForTheHistoryRow() {
    runBlocking {
      cache.save(ACCOUNT, "night", AnalysisDto(id = "old", status = "succeeded"))
      cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))
      cache.save(ACCOUNT, "night", "a2", analysis("a2", score = 90.0))
    }

    val runs = runBlocking { cache.cached(ACCOUNT, "night") }.associateBy { it.id }

    assertEquals(setOf("a1", "a2", "old"), runs.keys)
    assertEquals(90.0, runs.getValue("a2").result?.items?.single()?.value ?: 0.0, 0.001)
    assertTrue(runBlocking { cache.cached(ACCOUNT, "other") }.isEmpty())
  }

  @Test
  fun `results of one account are not visible to another`() {
    runBlocking { cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0)) }

    assertNull(runBlocking { cache.load("https://sleep.example|u2", "night", "a1") })
    assertFalse(runBlocking { cache.has("https://sleep.example|u2", "night", "a1") })
    assertTrue(runBlocking { cache.cached("https://sleep.example|u2", "night") }.isEmpty())
  }

  @Test
  fun `results come back after logging back into the first account`() {
    runBlocking {
      cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))
      cache.save("https://sleep.example|u2", "night", "b1", analysis("b1", score = 99.0))
    }

    assertEquals(
        70.0,
        runBlocking { cache.load(ACCOUNT, "night", "a1") }?.result?.items?.single()?.value ?: 0.0,
        0.001,
    )
    assertNull(runBlocking { cache.load(ACCOUNT, "night", "b1") })
    assertEquals(
        99.0,
        runBlocking { cache.load("https://sleep.example|u2", "night", "b1") }
            ?.result
            ?.items
            ?.single()
            ?.value ?: 0.0,
        0.001,
    )
  }

  @Test
  fun `deleting a night only removes that account's payloads`() {
    runBlocking {
      cache.save(ACCOUNT, "night", "a1", analysis("a1"))
      cache.save("https://sleep.example|u2", "night", "b1", analysis("b1"))

      cache.delete(ACCOUNT, "night")
    }

    assertNull(runBlocking { cache.load(ACCOUNT, "night", "a1") })
    assertNotNull(runBlocking { cache.load("https://sleep.example|u2", "night", "b1") })
  }
}
