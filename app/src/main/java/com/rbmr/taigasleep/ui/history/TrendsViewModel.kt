package com.rbmr.taigasleep.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.insights.TrendRanges
import com.rbmr.taigasleep.insights.TrendRecording
import com.rbmr.taigasleep.insights.Trends
import com.rbmr.taigasleep.insights.trendsFor
import com.rbmr.taigasleep.library.RecordingSource
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.profile.PersonalData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** What the Trends view draws: the chosen range and the computed nights of its calendar days. */
data class TrendsUiState(val rangeDays: Int, val data: Trends)

/**
 * The state holder of the Trends view. It reads the recordings that are on this phone and the cached
 * payload of the analysis each night shows, then hands them to [trendsFor]; it makes no network call
 * and owns no result of its own. A night that was deleted from the phone leaves the charts with it.
 */
class TrendsViewModel(
    private val library: RecordingSource,
    personalData: StateFlow<PersonalData>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** Reads the cached payloads away from the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val range = MutableStateFlow(TrendRanges.first())

  @OptIn(ExperimentalCoroutinesApi::class)
  val state: StateFlow<TrendsUiState> =
      combine(library.recordings, personalData, range) { recordings, profile, days ->
            Triple(recordings, profile, days)
          }
          .mapLatest { (recordings, profile, days) ->
            withContext(io) { read(recordings, profile, days) }
          }
          .stateIn(
              scope,
              SharingStarted.Eagerly,
              TrendsUiState(
                  rangeDays = range.value,
                  data = trendsFor(emptyList(), range.value, LocalDate.now(zone), zone),
              ),
          )

  /** Switch the range; a value the view does not offer is ignored. */
  fun setRange(days: Int) {
    if (days in TrendRanges) range.value = days
  }

  override fun onCleared() {
    scope.cancel()
  }

  private suspend fun read(
      recordings: List<RecordingView>,
      profile: PersonalData,
      days: Int,
  ): TrendsUiState {
    val today = LocalDate.now(zone)
    val first = today.minusDays((days - 1).toLong())
    // Only completed recordings of the range are read from the database; the rest cannot be drawn.
    val inRange =
        recordings.filter { view ->
          view.localId != null && !view.isRecording && view.wakeDate(zone).inRange(first, today)
        }
    val nights =
        inRange.map { view ->
          TrendRecording(
              key = view.key,
              localId = view.localId,
              remoteId = view.remoteId,
              analysisId = view.analysisId,
              startUtcMs = view.startUtcMs,
              endUtcMs = view.endUtcMs ?: 0L,
              items = itemsOf(library.cachedResult(view.key, view.analysisId)),
          )
        }
    return TrendsUiState(
        rangeDays = days,
        data =
            trendsFor(
                recordings = nights,
                rangeDays = days,
                today = today,
                zone = zone,
                usualBedtime = profile.usualBedtime,
                usualWakeTime = profile.usualWakeTime,
            ),
    )
  }

  private fun RecordingView.wakeDate(zone: ZoneId): LocalDate? =
      endUtcMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

  private fun LocalDate?.inRange(first: LocalDate, last: LocalDate): Boolean =
      this != null && !isBefore(first) && !isAfter(last)

  /** The numbers a cached payload carries, by item id; the charts need nothing else from it. */
  private fun itemsOf(analysis: AnalysisDto?): Map<String, Double> =
      analysis?.result?.items.orEmpty().mapNotNull { item ->
        item.value?.let { value -> item.id to value }
      }.toMap()

  /** Builds the state holder from the library and the personal data the app already holds. */
  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TrendsViewModel(
            library = context.appContainer.recordingLibrary,
            personalData = context.appContainer.personalDataStore.data,
        ) as T
  }
}
