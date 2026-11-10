package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.backend.StageSegmentDto
import com.rbmr.taigasleep.backend.StageTimelineDto
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val ACCOUNT = "https://sleep.example|u1"

class ResultCacheTest {
  private lateinit var root: File
  private lateinit var cache: ResultCache

  @Before
  fun setUp() {
    root = Files.createTempDirectory("taiga-results").toFile()
    cache = ResultCache(root)
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
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
    assertTrue(cache.save(ACCOUNT, "night", "a1", analysis("a1")))

    val loaded = cache.load(ACCOUNT, "night", "a1")

    assertEquals("a1", loaded?.id)
    assertEquals("wav2sleep", loaded?.algorithm)
    assertEquals(80.0, loaded?.result?.items?.single()?.value ?: 0.0, 0.001)
    assertEquals("light", loaded?.result?.stages?.segments?.single()?.stage)
    assertTrue(cache.has(ACCOUNT, "night", "a1"))
  }

  @Test
  fun runsOfTheSameNightDoNotReplaceEachOther() {
    cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))
    cache.save(ACCOUNT, "night", "a2", analysis("a2", score = 90.0))

    assertEquals(
        70.0,
        cache.load(ACCOUNT, "night", "a1")?.result?.items?.single()?.value ?: 0.0,
        0.001,
    )
    assertEquals(
        90.0,
        cache.load(ACCOUNT, "night", "a2")?.result?.items?.single()?.value ?: 0.0,
        0.001,
    )
    assertFalse(cache.has(ACCOUNT, "night", "a3"))
  }

  @Test
  fun anOlderPayloadLoadsWithoutTheFiguresItNoLongerHas() {
    // The flat shape the app cached before API v1: the result's fields sit beside the id.
    File(root, "night-a1.json")
        .writeText(
            """
            {"id":"a1","session":"s1","model":"sleep-ecg","model_version":"1.0.0","status":"done",
             "contract_version":3,
             "stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":2,
                       "segments":[{"stage":"deep","count":2}]},
             "sleep_quality":{"score":77.0},
             "metrics":{"total_sleep_s":60.0},"warnings":["short"]}
            """
        )

    val loaded = cache.load(ACCOUNT, "night", "a1")

    assertNotNull(loaded)
    assertEquals("a1", loaded?.id)
    assertEquals("sleep-ecg", loaded?.algorithm)
    assertEquals("1.0.0", loaded?.version)
    assertTrue(loaded?.isSucceeded == true)
    assertTrue(loaded?.result?.items?.isEmpty() == true)
    assertEquals("deep", loaded?.result?.stages?.segments?.single()?.stage)
    assertEquals(listOf("short"), loaded?.result?.warnings)
  }

  @Test
  fun aNightsSingleFileFromAnOlderAppOnlyAnswersItsOwnRun() {
    File(root, "night.json")
        .writeText("""{"id":"a1","model":"sleep-ecg","model_version":"1.0.0","status":"done"}""")

    assertEquals("a1", cache.load(ACCOUNT, "night", "a1")?.id)
    assertNull(cache.load(ACCOUNT, "night", "other"))
  }

  @Test
  fun aFileThatIsNotAnAnalysisIsIgnored() {
    File(root, "night-a1.json").writeText("[1,2,3]")

    assertNull(cache.load(ACCOUNT, "night", "a1"))
  }

  @Test
  fun deletingANightRemovesEveryRunOfIt() {
    cache.save(ACCOUNT, "night", "a1", analysis("a1"))
    cache.save("night", "a2", analysis("a2"))
    cache.save(ACCOUNT, "other", "a3", analysis("a3"))

    cache.delete(ACCOUNT, "night")

    assertNull(cache.load(ACCOUNT, "night", "a1"))
    assertNull(cache.load(ACCOUNT, "night", "a2"))
    assertNotNull(cache.load(ACCOUNT, "other", "a3"))
    assertTrue(cache.cached(ACCOUNT, "night").isEmpty())
  }

  @Test
  fun everyCachedRunOfNightComesBackForTheHistoryRow() {
    // The single payload an older app kept for a night is one more run.
    File(root, "night.json")
        .writeText("""{"id":"old","model":"sleep-ecg","model_version":"1.0.0","status":"done"}""")
    cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))
    cache.save(ACCOUNT, "night", "a2", analysis("a2", score = 90.0))

    val runs = cache.cached(ACCOUNT, "night").associateBy { it.id }

    assertEquals(setOf("a1", "a2", "old"), runs.keys)
    assertEquals(90.0, runs.getValue("a2").result?.items?.single()?.value ?: 0.0, 0.001)
    assertTrue(cache.cached(ACCOUNT, "other").isEmpty())
  }

  @Test
  fun `results of one account are not visible to another`() {
    cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))

    assertNull(cache.load("https://sleep.example|u2", "night", "a1"))
    assertFalse(cache.has("https://sleep.example|u2", "night", "a1"))
    assertTrue(cache.cached("https://sleep.example|u2", "night").isEmpty())
  }

  @Test
  fun `results come back after logging back into the first account`() {
    cache.save(ACCOUNT, "night", "a1", analysis("a1", score = 70.0))
    cache.save("https://sleep.example|u2", "night", "b1", analysis("b1", score = 99.0))

    assertEquals(
        70.0,
        cache.load(ACCOUNT, "night", "a1")?.result?.items?.single()?.value ?: 0.0,
        0.001,
    )
    assertNull(cache.load(ACCOUNT, "night", "b1"))
    assertEquals(
        99.0,
        cache.load("https://sleep.example|u2", "night", "b1")?.result?.items?.single()?.value
            ?: 0.0,
        0.001,
    )
  }

  @Test
  fun `deleting a night only removes that account's payloads`() {
    cache.save(ACCOUNT, "night", "a1", analysis("a1"))
    cache.save("https://sleep.example|u2", "night", "b1", analysis("b1"))

    cache.delete(ACCOUNT, "night")

    assertNull(cache.load(ACCOUNT, "night", "a1"))
    assertNotNull(cache.load("https://sleep.example|u2", "night", "b1"))
  }
}
