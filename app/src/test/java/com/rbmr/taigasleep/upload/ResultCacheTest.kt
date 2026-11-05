package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.AnalysisResultDto
import com.rbmr.taigasleep.remote.SleepQualityDto
import com.rbmr.taigasleep.remote.StageSegmentDto
import com.rbmr.taigasleep.remote.StageTimelineDto
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
                  sleep_quality = SleepQualityDto(score = score),
              ),
      )

  @Test
  fun aSavedAnalysisComesBackWithItsResult() {
    assertTrue(cache.save("night", "a1", analysis("a1")))

    val loaded = cache.load("night", "a1")

    assertEquals("a1", loaded?.id)
    assertEquals("wav2sleep", loaded?.algorithm)
    assertEquals(80.0, loaded?.result?.sleep_quality?.score ?: 0.0, 0.001)
    assertEquals("light", loaded?.result?.stages?.segments?.single()?.stage)
    assertTrue(cache.has("night", "a1"))
  }

  @Test
  fun runsOfTheSameNightDoNotReplaceEachOther() {
    cache.save("night", "a1", analysis("a1", score = 70.0))
    cache.save("night", "a2", analysis("a2", score = 90.0))

    assertEquals(70.0, cache.load("night", "a1")?.result?.sleep_quality?.score ?: 0.0, 0.001)
    assertEquals(90.0, cache.load("night", "a2")?.result?.sleep_quality?.score ?: 0.0, 0.001)
    assertFalse(cache.has("night", "a3"))
  }

  @Test
  fun aPayloadCachedByAnOlderAppStillLoadsAsAFinishedRun() {
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

    val loaded = cache.load("night", "a1")

    assertNotNull(loaded)
    assertEquals("a1", loaded?.id)
    assertEquals("sleep-ecg", loaded?.algorithm)
    assertEquals("1.0.0", loaded?.version)
    assertTrue(loaded?.isSucceeded == true)
    assertEquals(77.0, loaded?.result?.sleep_quality?.score ?: 0.0, 0.001)
    assertEquals("deep", loaded?.result?.stages?.segments?.single()?.stage)
    assertEquals(listOf("short"), loaded?.result?.warnings)
  }

  @Test
  fun aNightsSingleFileFromAnOlderAppOnlyAnswersItsOwnRun() {
    File(root, "night.json")
        .writeText("""{"id":"a1","model":"sleep-ecg","model_version":"1.0.0","status":"done"}""")

    assertEquals("a1", cache.load("night", "a1")?.id)
    assertNull(cache.load("night", "other"))
  }

  @Test
  fun aFileThatIsNotAnAnalysisIsIgnored() {
    File(root, "night-a1.json").writeText("[1,2,3]")

    assertNull(cache.load("night", "a1"))
  }

  @Test
  fun deletingANightRemovesEveryRunOfIt() {
    cache.save("night", "a1", analysis("a1"))
    cache.save("night", "a2", analysis("a2"))
    cache.save("other", "a3", analysis("a3"))

    cache.delete("night")

    assertNull(cache.load("night", "a1"))
    assertNull(cache.load("night", "a2"))
    assertNotNull(cache.load("other", "a3"))
  }
}
