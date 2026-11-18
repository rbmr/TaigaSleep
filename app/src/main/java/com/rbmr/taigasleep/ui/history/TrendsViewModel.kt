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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Trends view draws: the chosen range and the computed nights of its calendar days. */
data class TrendsUiState(val rangeDays: Int, val data: Trends)

/**
 * One recording as the trends read it. Upload progress changes the recording on every tick and
 * changes nothing here, so a list with the same nights in the same state is the same input and the
 * charts are not computed again.
 */
internal data class TrendSource(
    val key: String,
    val localId: String?,
    val remoteId: String?,
    val analysisId: String?,
    val startUtcMs: Long,
    /** The night's own end, or its start plus its duration when the view has none. */
    val endUtcMs: Long,
    /** False while the recording still runs: it has no end, so it is not drawn. */
    val finished: Boolean,
    val status: RecordingStatus,
)

/** The fields of [this] the trends depend on, and nothing else. */
internal fun RecordingView.toTrendSource(): TrendSource =
    TrendSource(
        key = key,
        localId = localId,
        remoteId = remoteId,
        analysisId = analysisId,
        startUtcMs = startUtcMs,
        endUtcMs = trendEndUtcMs(),
        finished = !isRecording,
        status = status,
    )

/**
 * The state holder of the Trends view. It reads the recordings that are on this phone and the cached
 * payload of the analysis each night shows, then hands them to [trendsFor]; it makes no network call
 * and owns no result of its own. A night that was deleted from the phone leaves the charts with it.
 *
 * The nights are read again only when a field the charts draw changes, and a payload that was read
 * once is kept by its analysis, so moving an upload along does not touch the phone's storage.
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

  /** Results being read from the server, by the recording and the analysis they belong to. */
  private val fetching = ConcurrentHashMap.newKeySet<String>()

  /** The items of every result that was read, by the analysis they belong to. */
  private val items = ConcurrentHashMap<String, Map<String, Double>>()

  /** The recordings the charts read, without the fields an upload tick changes. */
  private val sources: Flow<List<TrendSource>> =
      library.recordings
          .map { recordings -> recordings.map { it.toTrendSource() } }
          .distinctUntilChanged()

  @OptIn(ExperimentalCoroutinesApi::class)
  val state: StateFlow<TrendsUiState> =
      combine(sources, personalData, range, fetched) { recordings, profile, days, _ ->
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
      recordings: List<TrendSource>,
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
        recordings.filter { it.finished }.filter { source ->
          val date = nightDate(source.startUtcMs, zone)
          source.endUtcMs > source.startUtcMs && date >= first && date <= today
        }
    val cached = inRange.map { source -> source to itemsOf(source) }
    fetchMissingResults(cached)
    val nights = cached.map { (source, values) -> trendRecording(source, values.orEmpty()) }
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
   * values to draw. Its result is read once in the background and the trends are computed again. The
   * recording alone is not the key: another run of it is another result to read.
   */
  private fun fetchMissingResults(nights: List<Pair<TrendSource, Map<String, Double>?>>) {
    val missing =
        nights
            .filter { (source, values) ->
              values == null &&
                  source.remoteId != null &&
                  source.status == RecordingStatus.READY &&
                  fetching.add(analysisKey(source))
            }
            .map { it.first }
    if (missing.isEmpty()) return
    scope.launch {
      for (source in missing) {
        val key = analysisKey(source)
        val read =
            withContext(io) {
              library.refreshRecording(source.key, source.remoteId, source.analysisId)
            }
        // A read without a payload is tried again the next time the trends are computed.
        val analysis = read.getOrNull()
        if (analysis != null) {
          items[key] = itemsOf(analysis)
          fetched.update { it + 1 }
        }
        fetching.remove(key)
      }
    }
  }

  /**
   * The numbers the night's cached payload carries, by item id; the charts need nothing else from
   * it. A payload that was read once is kept by its analysis, so the next computation reads the
   * phone no further.
   */
  private suspend fun itemsOf(source: TrendSource): Map<String, Double>? {
    val key = analysisKey(source)
    val known =
        items[key] ?: library.cachedResult(source.key, source.analysisId)?.let { itemsOf(it) }
    return known?.also { items[key] = it }
  }

  /** The numbers of one payload, by item id. */
  private fun itemsOf(analysis: AnalysisDto): Map<String, Double> =
      analysis.result?.items.orEmpty().mapNotNull { item ->
        item.value?.let { value -> item.id to value }
      }.toMap()

  /** The one result a night shows, by the recording and the analysis it belongs to. */
  private fun analysisKey(source: TrendSource): String =
      "${source.key}/${source.analysisId.orEmpty()}"

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

/** One recording as the trends read it, with the items of the analysis it shows. */
internal fun trendRecording(source: TrendSource, items: Map<String, Double>): TrendRecording =
    TrendRecording(
        key = source.key,
        localId = source.localId,
        remoteId = source.remoteId,
        analysisId = source.analysisId,
        startUtcMs = source.startUtcMs,
        endUtcMs = source.endUtcMs,
        items = items,
    )
