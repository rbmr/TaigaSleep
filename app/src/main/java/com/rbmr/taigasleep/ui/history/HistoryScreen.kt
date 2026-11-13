package com.rbmr.taigasleep.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.library.RecordingView
import com.rbmr.taigasleep.library.formatDuration
import com.rbmr.taigasleep.library.formatRecordingDate
import com.rbmr.taigasleep.library.formatRecordingTime
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.ui.components.Pressable
import com.rbmr.taigasleep.ui.components.ProgressTrack
import com.rbmr.taigasleep.ui.components.ProgressTrackState
import com.rbmr.taigasleep.ui.components.SegmentedControl
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StageBar
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.StatusPill
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/**
 * History shows the recordings on this phone and the trends they make: one list without grouping,
 * and one switch at the top between the two views.
 *
 * A server that is missing, unreachable or has no accepted login is said once above the list,
 * because without one the recordings stay unanalyzed. Tapping that banner opens the server page.
 *
 * The screen only draws the state its view model holds (see [HistoryViewModel]).
 */
@Composable
fun HistoryScreen(
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
    onOpenServer: () -> Unit,
) {
  val context = LocalContext.current
  val factory = remember { HistoryViewModel.Factory(context) }
  val viewModel: HistoryViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { viewModel.onOpen() }
  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }

  HistoryScreen(state = state, onOpenRecording = onOpenRecording, onOpenServer = onOpenServer)
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
    onOpenServer: () -> Unit,
) {
  // The switch is presentation state: it lives while the app runs and History opens on the
  // recordings.
  var trends by rememberSaveable { mutableStateOf(false) }
  TaigaScreen(title = stringResource(R.string.history_title), scrollable = false) {
    // The switch floats over the list or the charts, which scroll behind it: nothing but the
    // control itself is drawn there. The content leaves room for it below its last item.
    Box(Modifier.weight(1f).fillMaxWidth()) {
      if (trends) {
        TrendsScreen(
            modifier = Modifier.fillMaxSize(),
            bottomPadding = SwitcherClearance,
        )
      } else {
        RecordingsList(
            state = state,
            onOpenRecording = onOpenRecording,
            onOpenServer = onOpenServer,
            modifier = Modifier.fillMaxSize(),
        )
      }
      SegmentedControl(
          options =
              listOf(
                  stringResource(R.string.history_mode_nights),
                  stringResource(R.string.history_mode_trends),
              ),
          selectedIndex = if (trends) 1 else 0,
          onSelect = { trends = it == 1 },
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .padding(bottom = MaterialTheme.taiga.space.m),
          fillWidth = true,
          floating = true,
      )
    }
  }
}

/** The 48 dp switch with 16 dp above and below it: what a list keeps free under its last item. */
private val SwitcherClearance = 80.dp

/** The recordings, one list without grouping. The empty state and the server warning stay. */
@Composable
private fun RecordingsList(
    state: HistoryUiState,
    onOpenRecording: (localId: String?, remoteId: String?, analysisId: String?) -> Unit,
    onOpenServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val space = MaterialTheme.taiga.space
  LazyColumn(
      modifier.fillMaxWidth(),
      contentPadding = PaddingValues(bottom = SwitcherClearance),
      verticalArrangement = Arrangement.spacedBy(space.m),
  ) {
    val warning = state.serverWarning
    if (warning != null) {
      item {
        StatusBanner(
            kind = StatusKind.Warn,
            title = stringResource(warning.titleRes),
            body = stringResource(R.string.history_server_warning_body),
            onClick = onOpenServer,
        )
      }
    }

    if (state.items.isEmpty()) {
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.history_empty_title),
              style = MaterialTheme.taiga.text.section,
          )
          Text(
              text = stringResource(R.string.history_empty_body),
              style = MaterialTheme.taiga.text.body,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = space.s),
          )
        }
      }
    } else {
      item {
        TaigaCard(contentPadding = PaddingValues(0.dp)) {
          state.items.forEachIndexed { index, item ->
            if (index > 0) SettingsDivider()
            RecordingRow(
                item = item,
                onOpen = { onOpenRecording(item.localId, item.remoteId, item.analysisId) },
            )
          }
        }
      }
    }
  }
}

/**
 * One recording in a fixed-height row: the date, the clock times and length, the score or the state
 * pill, and an 8 dp bar at the bottom. The bar is a stage bar, a progress bar, an indeterminate bar
 * or an empty track, so the row never changes height.
 */
@Composable
private fun RecordingRow(item: RecordingView, onOpen: () -> Unit) {
  val space = MaterialTheme.taiga.space
  val end = item.endUtcMs?.takeIf { it > item.startUtcMs }
  val meta =
      if (end != null) {
        stringResource(
            R.string.history_times,
            formatRecordingTime(item.startUtcMs),
            formatRecordingTime(end),
            formatDuration(end - item.startUtcMs),
        )
      } else {
        stringResource(
            R.string.history_started,
            formatRecordingTime(item.startUtcMs),
            formatDuration(item.durationMs),
        )
      }
  val date = formatRecordingDate(item.startUtcMs)
  val pill = rowPill(item)
  Pressable(
      onClick = onOpen,
      modifier = Modifier.fillMaxWidth(),
      shape = RectangleShape,
      role = Role.Button,
      onClickLabel = date,
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                // The text carries a few dp of line spacing above its glyphs and the bar has
                // none, so the top inset is smaller to make both edges look equal.
                .padding(start = space.m, end = space.m, top = space.s + space.xs, bottom = space.m)
    ) {
      Row(
          modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
              text = date,
              style = MaterialTheme.taiga.text.row,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              text = meta,
              style = MaterialTheme.taiga.text.meta,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
        if (pill == null) {
          Column(horizontalAlignment = Alignment.End) {
            Text(
                text = item.sleepScore?.roundToInt()?.toString() ?: "-",
                style = MaterialTheme.taiga.text.section,
            )
            Text(
                text = stringResource(R.string.history_sleep_score_label),
                style = MaterialTheme.taiga.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          StatusPill(state = pill.state, label = pill.label, modifier = Modifier.padding(start = space.s))
        }
      }
      Spacer(Modifier.height(space.s))
      if (item.status == RecordingStatus.READY && item.segments.isNotEmpty()) {
        StageBar(item.segments, height = 8.dp)
      } else {
        ProgressTrack(
            state = rowTrack(item),
            description = rowTrackDescription(item),
        )
      }
    }
  }
}

/** The state of a recording as one pill, or null when the score stands in its place. */
private data class RowPill(val label: String, val state: StatusPillState)

@Composable
private fun rowPill(item: RecordingView): RowPill? =
    when (item.status) {
      RecordingStatus.READY -> null
      RecordingStatus.RECORDING ->
          RowPill(stringResource(R.string.history_recording), StatusPillState.Busy)
      RecordingStatus.LOCAL_ONLY ->
          RowPill(stringResource(R.string.history_local_only), StatusPillState.Neutral)
      RecordingStatus.QUEUED ->
          RowPill(stringResource(R.string.history_queued), StatusPillState.Neutral)
      RecordingStatus.UPLOADING ->
          RowPill(
              stringResource(R.string.history_uploading, item.percent),
              StatusPillState.Busy,
          )
      RecordingStatus.PROCESSING ->
          RowPill(stringResource(R.string.history_processing), StatusPillState.Busy)
      RecordingStatus.NOT_ANALYZED ->
          RowPill(stringResource(R.string.history_not_analyzed), StatusPillState.Neutral)
      RecordingStatus.FAILED ->
          when (item.failure) {
            UploadFailure.ANALYSIS ->
                RowPill(
                    stringResource(R.string.history_status_analysis_failed),
                    StatusPillState.Bad,
                )
            UploadFailure.LOGIN ->
                RowPill(stringResource(R.string.history_status_login), StatusPillState.Warn)
            UploadFailure.VERSION ->
                RowPill(stringResource(R.string.history_status_update), StatusPillState.Warn)
            else ->
                RowPill(
                    stringResource(R.string.history_status_upload_failed),
                    StatusPillState.Bad,
                )
          }
    }

private fun rowTrack(item: RecordingView): ProgressTrackState =
    when (item.status) {
      RecordingStatus.UPLOADING -> ProgressTrackState.Determinate(item.progress)
      RecordingStatus.QUEUED,
      RecordingStatus.PROCESSING -> ProgressTrackState.Indeterminate
      else -> ProgressTrackState.Empty
    }

@Composable
private fun rowTrackDescription(item: RecordingView): String? =
    when (item.status) {
      RecordingStatus.UPLOADING ->
          stringResource(R.string.history_uploading, item.percent)
      RecordingStatus.QUEUED -> stringResource(R.string.history_queued)
      RecordingStatus.PROCESSING -> stringResource(R.string.history_processing)
      else -> null
    }

private val RecordingView.percent: Int
  get() = (progress * 100).toInt().coerceIn(0, 100)

/** The one line that says why a recording is not analyzed yet; the body under it is the same. */
private val ServerWarning.titleRes: Int
  get() =
      when (this) {
        ServerWarning.NOT_CONFIGURED -> R.string.history_server_warning_no_server
        ServerWarning.UNREACHABLE -> R.string.history_server_warning_unreachable
        ServerWarning.LOGIN_NEEDED -> R.string.history_server_warning_login_needed
      }
