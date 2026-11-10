package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.recording.store.Recording
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun recording(id: String = "r1", complete: Boolean = true) =
    RecordingDto(
        id = id,
        state = "stopped",
        device = "H10",
        started_at = "2026-10-23T20:30:00Z",
        duration_s = 3600.0,
        complete = complete,
    )

private fun analysis(
    id: String,
    status: String,
    created: String = "2026-10-24T05:00:00Z",
    updated: String = created,
    error: String = "",
) =
    AnalysisDto(
        id = id,
        recording = "r1",
        algorithm = "baseline",
        version = "1.0.0",
        status = status,
        created = created,
        updated = updated,
        error = error,
    )

/** Which nights a reconcile reads again: everything not settled, and nothing that is. */
private const val ACCOUNT = "https://sleep.example|u1"

class PendingRecordingsTest {
  @get:Rule val temporary = TemporaryFolder()

  private fun summary(
      analyses: List<AnalysisDto>,
      id: String = "r1",
      complete: Boolean = true,
  ) = RecordingSummaryDto(recording = recording(id, complete), analyses = analyses)

  private fun record(key: String = "remote-r1", phase: UploadPhase) =
      UploadRecord(id = key, onServer = true, phase = phase)

  /** A result cache holding one finished run of [key]. */
  private fun cacheWith(key: String, analysisId: String): ResultCache =
      ResultCache(temporary.newFolder("results")).also { cache ->
        cache.save(ACCOUNT, key, analysisId, AnalysisDto(id = analysisId, status = "succeeded"))
      }

  private fun pending(
      summaries: List<RecordingSummaryDto>,
      vararg records: UploadRecord,
      local: List<Recording> = emptyList(),
      results: ResultCache? = null,
  ): List<PendingRecording> {
    val byKey = records.associateBy { it.id }
    val nights =
        buildRecordings(
            recordings = local,
            records = byKey,
            remote = RecordingSummaryListDto(summaries),
            results = results,
            account = ACCOUNT,
        )
    return pendingRecordings(nights, byKey)
  }

  @Test
  fun `a night whose result is on the phone is settled`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "succeeded")))),
            record(phase = UploadPhase.DONE),
            results = cacheWith("remote-r1", "a1"),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a failed night is settled`() {
    assertTrue(pending(listOf(summary(listOf(analysis("a1", "failed", error = "x"))))).isEmpty())
  }

  @Test
  fun `a finished analysis whose result is not on the phone is read for it`() {
    val result = pending(listOf(summary(listOf(analysis("a1", "succeeded")))))

    assertEquals(listOf(PendingRecording("remote-r1", "r1", "a1")), result)
  }

  @Test
  fun `queued and running analyses are pending`() {
    for (status in listOf("queued", "running")) {
      val result = pending(listOf(summary(listOf(analysis("a1", status)))))

      assertEquals(status, listOf("remote-r1"), result.map { it.key })
    }
  }

  @Test
  fun `a night stored as processing that the server finished is settled by a read`() {
    // It finished while the app was closed: the stored state still says processing.
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "succeeded")))),
            record(phase = UploadPhase.PROCESSING),
        )

    assertEquals(listOf("remote-r1"), result.map { it.key })
  }

  @Test
  fun `a night stored as processing that the server failed is settled by a read`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "failed", error = "x")))),
            record(phase = UploadPhase.PROCESSING),
        )

    assertEquals(listOf("remote-r1"), result.map { it.key })
  }

  @Test
  fun `a night stored as processing with nothing to settle is left alone`() {
    // The analysis was never requested, or the server no longer lists one: a read would only
    // repeat what the list already says.
    val result =
        pending(listOf(summary(emptyList())), record(phase = UploadPhase.PROCESSING))

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a night uploaded with automatic analysis off is not read again and again`() {
    val result = pending(listOf(summary(emptyList())), record(phase = UploadPhase.PROCESSING))

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a night with analyses on the server that the app never heard of is read`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "failed", error = "x")))),
            record(phase = UploadPhase.PROCESSING),
        )

    assertEquals(listOf("remote-r1"), result.map { it.key })
  }

  @Test
  fun `a processing night older than the list reaches is read by its own id`() {
    // On the phone, uploaded, and past the 100 recordings the History list reaches.
    val id = "20261001_223000-k3f9"
    val night =
        Recording(
            id = id,
            dir = File("/tmp/$id"),
            state = RecordingPhase.STOPPED,
            startUtcMs = 1_790_000_000_000,
            endUtcMs = 1_790_003_600_000,
            sizeBytes = 10,
            sensorName = "Polar H10",
        )

    val result =
        pending(emptyList(), record(key = id, phase = UploadPhase.PROCESSING), local = listOf(night))

    // The list does not hold it, so the read has no run to prefer; it goes by the recording id.
    assertEquals(listOf(PendingRecording(id, id, null)), result)
  }

  @Test
  fun `a recording the server has not sealed is still uploading and is left alone`() {
    val result =
        pending(
            listOf(summary(listOf(analysis("a1", "running")), complete = false)),
            record(phase = UploadPhase.PROCESSING),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `a night that is only on the phone is not read`() {
    val result =
        pending(emptyList(), UploadRecord(id = "20261023_223000-k3f9", phase = UploadPhase.QUEUED))

    assertTrue(result.isEmpty())
  }

  @Test
  fun `only the pending nights of several are returned`() {
    val result =
        pending(
            listOf(
                summary(listOf(analysis("a1", "succeeded")), id = "r1"),
                summary(listOf(analysis("a2", "running")), id = "r2"),
                summary(listOf(analysis("a3", "failed", error = "x")), id = "r3"),
            ),
            record(key = "remote-r1", phase = UploadPhase.DONE),
            results = cacheWith("remote-r1", "a1"),
        )

    assertEquals(listOf("remote-r2"), result.map { it.key })
    assertFalse(result.any { it.key == "remote-r1" })
  }
}
