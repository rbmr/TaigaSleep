package com.rbmr.taigasleep.upload

import com.rbmr.taigasleep.remote.AnalysisDto
import com.rbmr.taigasleep.remote.RecordingDto
import com.rbmr.taigasleep.remote.RecordingSummaryDto
import com.rbmr.taigasleep.remote.RecordingSummaryListDto
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
