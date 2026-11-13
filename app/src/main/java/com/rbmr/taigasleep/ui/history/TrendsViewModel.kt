package com.rbmr.taigasleep.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.insights.TrendRanges
import com.rbmr.taigasleep.insights.nightDate
import com.rbmr.taigasleep.insights.TrendRecording
import com.rbmr.taigasleep.insights.Trends
import com.rbmr.taigasleep.insights.trendsFor
import com.rbmr.taigasleep.library.RecordingSource
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.profile.PersonalData
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

  /** Counts the results that were read from the server, so the trends are computed again. */
  private val fetched = MutableStateFlow(0)

  /** Recordings whose result is being read from the server or was read already. */
  private val fetching = ConcurrentHashMap.newKeySet<String>()

  @OptIn(ExperimentalCoroutinesApi::class)
  val state: StateFlow<TrendsUiState> =
      combine(library.recordings, personalData, range, fetched) { recordings, profile, days, _ ->
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
    // The range ends today or yesterday, see trendsFor; one more day keeps either window whole.
    val first = today.minusDays(days.toLong())
    // The library's list is the source: a night that only exists on the server is a night too, and
    // its saved result is read from the cache. The night being recorded has no end yet, so it is
    // not drawn.
    val inRange =
        recordings.filterNot { it.isRecording }.filter { view ->
          val date = nightDate(view.startUtcMs, zone)
          view.trendEndUtcMs() > view.startUtcMs && date >= first && date <= today
        }
    val cached = inRange.map { view -> view to library.cachedResult(view.key, view.analysisId) }
    fetchMissingResults(cached)
    val nights = cached.map { (view, result) -> trendRecording(view, itemsOf(result)) }
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

  /**
   * A night that is analysed on the server but whose result was never opened on this phone has no
   * values to draw. Its result is read once in the background and the trends are computed again.
   */
  private fun fetchMissingResults(nights: List<Pair<RecordingView, AnalysisDto?>>) {
    val missing =
        nights
            .filter { (view, result) ->
              result == null &&
                  view.remoteId != null &&
                  view.status == RecordingStatus.READY &&
                  fetching.add(view.key)
            }
            .map { it.first }
    if (missing.isEmpty()) return
    scope.launch {
      for (view in missing) {
        val read = withContext(io) { library.refreshRecording(view.key, view.remoteId, view.analysisId) }
        // A failed read is tried again the next time the trends are computed.
        if (read.isSuccess) fetched.update { it + 1 } else fetching.remove(view.key)
      }
    }
  }

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

/**
 * The end the trends use: the recording's own end, or the start plus the duration when the view has
 * none, which is how a night that is only in the server list still has an end time. Both values are
 * on [RecordingView], so this needs no server.
 */
internal fun RecordingView.trendEndUtcMs(): Long = endUtcMs ?: (startUtcMs + durationMs)

/** One recording view as the trends read it, with the items of the analysis it shows. */
internal fun trendRecording(view: RecordingView, items: Map<String, Double>): TrendRecording =
    TrendRecording(
        key = view.key,
        localId = view.localId,
        remoteId = view.remoteId,
        analysisId = view.analysisId,
        startUtcMs = view.startUtcMs,
        endUtcMs = view.trendEndUtcMs(),
        items = items,
    )
