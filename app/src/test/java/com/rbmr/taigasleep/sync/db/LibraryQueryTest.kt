package com.rbmr.taigasleep.sync.db

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.sync.LibraryEntry
import com.rbmr.taigasleep.sync.PendingDelete
import com.rbmr.taigasleep.sync.RecordingRows
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one query a recording list is read from: what it joins, and what it leaves out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryQueryTest {
  private lateinit var database: TaigaDatabase

  @Before
  fun setUp() {
    database = testDatabase()
  }

  @After
  fun tearDown() {
    database.close()
  }

  private val account = "https://sleep.example|me@example.com"
  private val other = "https://sleep.example|you@example.com"

  private fun rows() = RecordingRows(database.library())

  private fun observe(localIds: List<String> = emptyList()): List<LibraryEntry> =
      runBlocking { rows().observe(account, localIds).first() }

  private fun load(localIds: List<String> = emptyList()): List<LibraryEntry> =
      runBlocking { rows().load(account, localIds) }

  @Test
  fun `a recording on this phone is joined with its state, its server row and its runs`() {
    runBlocking {
      val record = UploadRecord(id = "local", onServer = true, phase = UploadPhase.DONE)
      database.uploadStates().upsert(record.toRow(account))
      database.remoteRecordings().upsert(listOf(recording("local").toRow(account, 0)))
      database
          .remoteRecordings()
          .upsertAnalyses(listOf(analysis("a1").toRemoteAnalysisRow(account)))
      database.analysisResults().upsert(analysis("a1").toResultRow(account, "local"))
      database.remoteRecordings().select(RemoteSelectionRow(account, "local", "a1"))
    }

    val entry = observe(localIds = listOf("local")).single()

    assertEquals("local", entry.key)
    assertEquals(UploadPhase.DONE, entry.record?.phase)
    assertEquals("local", entry.recording?.id)
    assertEquals(listOf("a1"), entry.runs.map { it.id })
    assertEquals(listOf("a1"), entry.cachedResults.map { it.id })
    assertEquals("a1", entry.selectedAnalysisId)
    assertFalse(entry.hidden)
  }

  @Test
  fun `a recording only on the server is keyed by its remote key`() {
    runBlocking {
      database.remoteRecordings().upsert(listOf(recording("remote1").toRow(account, 0)))
    }

    val entry = observe().single()

    assertEquals("remote-remote1", entry.key)
    assertEquals("remote1", entry.recording?.id)
    assertNull(entry.record)
  }

  @Test
  fun `another account is never read`() {
    runBlocking {
      database.remoteRecordings().upsert(listOf(recording("mine").toRow(account, 0)))
      database.remoteRecordings().upsert(listOf(recording("yours").toRow(other, 0)))
      database.uploadStates().upsert(UploadRecord(id = "mine", onServer = true).toRow(account))
      database.uploadStates().upsert(UploadRecord(id = "yours", onServer = true).toRow(other))
    }

    val entries = observe(localIds = listOf("mine", "yours"))

    assertEquals(listOf("mine"), entries.map { it.key })
    assertEquals("mine", entries.single().record?.id)
    assertEquals("mine", entries.single().recording?.id)
  }

  @Test
  fun `a queued delete hides its recording`() {
    runBlocking {
      database.remoteRecordings().upsert(listOf(recording("gone").toRow(account, 0)))
      database.pendingDeletes().upsert(PendingDelete("gone", account, 1).toRow(account))
    }

    assertTrue(observe().single().hidden)
  }

  @Test
  fun `a delete that finished keeps the recording out of a list fetched later`() {
    val cache = RemoteHistoryCache(database.remoteRecordings())
    val list = RecordingSummaryListDto(listOf(RecordingSummaryDto(recording("gone"))))
    runBlocking {
      cache.save(account, list)
      cache.remove(account, "gone")
      // A fetch that started before the delete finished still carries the recording.
      cache.save(account, list)
    }

    assertTrue(runBlocking { cache.removed(account) }.contains("gone"))
    // The marker keeps its row, and the row is the only thing that says the recording is gone.
    assertTrue(load().single().hidden)
  }

  @Test
  fun `a recording the server lists without a run has none`() {
    runBlocking {
      database.remoteRecordings().upsert(listOf(recording("remote1").toRow(account, 0)))
    }

    val entry = observe().single()

    assertTrue(entry.runs.isEmpty())
    assertTrue(entry.cachedResults.isEmpty())
  }

  @Test
  fun `a recording with no server row is drawn from the payloads this phone holds`() {
    runBlocking {
      database.analysisResults().upsert(analysis("a1").toResultRow(account, "local"))
    }

    val entry = observe(localIds = listOf("local")).single()

    assertEquals("local", entry.key)
    assertNull(entry.recording)
    assertTrue(entry.runs.isEmpty())
    assertEquals(listOf("a1"), entry.cachedResults.map { it.id })
  }

  private fun recording(id: String) = RecordingDto(id = id, state = "stopped", complete = true)

  private fun analysis(id: String) =
      AnalysisDto(id = id, recording = "local", algorithm = "baseline", status = "succeeded")
}
