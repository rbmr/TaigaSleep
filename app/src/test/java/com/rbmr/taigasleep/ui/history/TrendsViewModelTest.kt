package com.rbmr.taigasleep.ui.history

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.ResultItemDto
import com.rbmr.taigasleep.insights.TrendItems
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.library.BackupState
import com.rbmr.taigasleep.library.RecordingSource
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.profile.PersonalData
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A source that hands out the library's list and cached payloads; only reading a recording works. */
private class FakeRecordingSource(
    nights: List<RecordingView>,
    results: Map<String, AnalysisDto> = emptyMap(),
) : RecordingSource {
  private val cached = results.toMutableMap()
  override val recordings: MutableStateFlow<List<RecordingView>> = MutableStateFlow(nights)
  override val remoteStatus: MutableStateFlow<RemoteStatus> =
      MutableStateFlow<RemoteStatus>(RemoteStatus.NotConfigured)
  val cachedKeys = mutableListOf<String>()

  override fun recording(key: String): RecordingView? =
      recordings.value.firstOrNull { it.key == key }

  override suspend fun cachedResult(key: String, analysisId: String?): AnalysisDto? {
    cachedKeys += key
    return cached[key]
  }

  /** The results a server read stores: reading a recording makes its result cached. */
  val onServer = mutableMapOf<String, AnalysisDto>()
  val refreshed = mutableListOf<String>()

  override suspend fun refreshRecording(
      key: String,
      remoteId: String?,
      analysisId: String?,
  ): Result<AnalysisDto?> {
    refreshed += key
    val result = onServer[key] ?: return Result.failure(IllegalStateException("offline"))
    cached[key] = result
    return Result.success(result)
  }

  override suspend fun compatibleAlgorithms(recording: RecordingView): Result<List<AlgorithmDto>> =
      refuse()

  override suspend fun requestManual(
      recording: RecordingView,
      algorithmId: String,
  ): Result<Unit> = refuse()

  override fun selectAnalysis(recording: RecordingView, analysisId: String): Unit = refuse()

  override suspend fun uploadedElsewhere(localId: String): Boolean = refuse()

  private fun <T> refuse(): T = throw AssertionError("the trends must not fetch or write")
}

private fun night(
    key: String,
    localId: String?,
    remoteId: String?,
    startUtcMs: Long,
    endUtcMs: Long?,
    durationMs: Long,
    analysisId: String? = null,
) = RecordingView(
    key = key,
    localId = localId,
    remoteId = remoteId,
    startUtcMs = startUtcMs,
    endUtcMs = endUtcMs,
    durationMs = durationMs,
    device = "Polar H10",
    status = RecordingStatus.READY,
    progress = 1f,
    failure = null,
    errorDetail = null,
    warning = null,
    analyses =
        analysisId
            ?.let {
              listOf(
                  Analysis(
                      id = it,
                      algorithm = "stager",
                      version = "1",
                      status = AnalysisStatus.DONE,
                  )
              )
            }
            .orEmpty(),
    selectedAnalysisId = analysisId,
    backup = BackupState(),
    local = null,
    remote = null,
    resultDownloaded = analysisId != null,
)

private fun analysis(id: String, items: Map<String, Double>) =
    AnalysisDto(
        id = id,
        recording = "r1",
        status = "succeeded",
        result =
            AnalysisResultDto(
                items =
                    items.map { (itemId, value) ->
                      ResultItemDto(kind = "number", id = itemId, value = value)
                    }
            ),
    )

class TrendsViewModelTest {
  private val zone = ZoneId.of("UTC")

  private fun at(date: LocalDate, time: String): Long =
      LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

  private fun viewModel(source: FakeRecordingSource) =
      TrendsViewModel(
          library = source,
          personalData = MutableStateFlow(PersonalData()),
          zone = zone,
          dispatcher = Dispatchers.Unconfined,
          io = Dispatchers.Unconfined,
      )

  @Test
  fun `a night that only exists on the server is drawn from its cached result`() {
    val wake = LocalDate.now(zone).minusDays(1)
    val start = at(wake.minusDays(1), "23:00")
    val end = at(wake, "07:00")
    val remoteOnly =
        night(
            key = "remote-key",
            localId = null,
            remoteId = "r1",
            startUtcMs = start,
            endUtcMs = end,
            durationMs = end - start,
            analysisId = "a1",
        )
    val source =
        FakeRecordingSource(
            listOf(remoteOnly),
            mapOf(
                "remote-key" to
                    analysis(
                        "a1",
                        mapOf(
                            TrendItems.TOTAL_SLEEP to 7.0 * 3600,
                            TrendItems.DATA_QUALITY to 90.0,
                        ),
                    )
            ),
        )

    val trends = viewModel(source).state.value.data
    val drawn = trends.nights.filterNotNull().single()

    assertEquals("remote-key", drawn.key)
    assertNull(drawn.localId)
    assertEquals(wake.minusDays(1), drawn.date)
    assertEquals(7.0 * 3600, drawn.totalSleepSeconds ?: 0.0, 0.001)
    assertEquals(7.0 * 3600, trends.totalSleep.value ?: 0.0, 0.001)
    assertEquals(listOf("remote-key"), source.cachedKeys)
  }

  @Test
  fun `a night without an end uses its start plus its duration`() {
    val wake = LocalDate.now(zone).minusDays(1)
    val start = at(wake.minusDays(1), "23:00")
    val duration = 8 * 3_600_000L
    val withoutEnd =
        night(
            key = "no-end",
            localId = "no-end",
            remoteId = null,
            startUtcMs = start,
            endUtcMs = null,
            durationMs = duration,
        )

    val drawn =
        viewModel(FakeRecordingSource(listOf(withoutEnd)))
            .state
            .value
            .data
            .nights
            .filterNotNull()
            .single()

    assertEquals(wake.minusDays(1), drawn.date)
    assertEquals(7 * 60, drawn.endMinuteOfDay)
    assertEquals(8 * 3600L, drawn.durationSeconds)
  }

  @Test
  fun `a server night whose result is not on the phone is read once and then drawn`() {
    val night = LocalDate.now(zone).minusDays(2)
    val start = at(night, "23:00")
    val end = at(night.plusDays(1), "07:00")
    val remoteOnly =
        night(
            key = "remote-key",
            localId = null,
            remoteId = "r1",
            startUtcMs = start,
            endUtcMs = end,
            durationMs = end - start,
            analysisId = "a1",
        )
    val source = FakeRecordingSource(listOf(remoteOnly))
    source.onServer["remote-key"] =
        analysis("a1", mapOf(TrendItems.TOTAL_SLEEP to 6.0 * 3600, TrendItems.DATA_QUALITY to 90.0))

    val drawn = viewModel(source).state.value.data.nights.filterNotNull().single()

    assertEquals(night, drawn.date)
    assertEquals(6.0 * 3600, drawn.totalSleepSeconds ?: 0.0, 0.001)
    assertEquals(listOf("remote-key"), source.refreshed)
  }

  @Test
  fun `a recording that starts after midnight is drawn on the previous date`() {
    val evening = LocalDate.now(zone).minusDays(3)
    val start = at(evening.plusDays(1), "00:39")
    val end = at(evening.plusDays(1), "07:30")
    val late =
        night(
            key = "late",
            localId = "late",
            remoteId = null,
            startUtcMs = start,
            endUtcMs = end,
            durationMs = end - start,
        )

    val drawn = viewModel(FakeRecordingSource(listOf(late))).state.value.data.nights.filterNotNull().single()

    assertEquals(evening, drawn.date)
  }
}
