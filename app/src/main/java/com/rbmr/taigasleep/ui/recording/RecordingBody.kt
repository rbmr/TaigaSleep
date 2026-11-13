package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.insights.SleepScoreCardState
import com.rbmr.taigasleep.insights.sleepScoreCardState
import com.rbmr.taigasleep.library.RecordingStatus
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * The body of the recording screen: the summary, the analysis the recording was made with, the
 * sections that detail it, and the recording itself. Nothing here decides anything.
 */
@Composable
internal fun RecordingBody(
    state: RecordingDetailUiState,
    modifier: Modifier = Modifier,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onLogin: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenScore: () -> Unit,
    onExpand: (String) -> Unit,
    onExport: () -> Unit,
    onDownloadRaw: () -> Unit,
    onDeleteRaw: () -> Unit,
    onDelete: () -> Unit,
) {
  val run = state.result
  val analysis = run?.result
  val processed = run?.isSucceeded == true
  // Whether the recording has a finished analysis is what the library knows, not whether its
  // result has been read yet. While that result is on its way the screen shows the layout it fills.
  val analysed = state.analysed
  val reading = state.loading && analysed && analysis == null
  val space = MaterialTheme.taiga.space

  LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(bottom = space.l),
      verticalArrangement = Arrangement.spacedBy(space.l),
  ) {
    if (reading) {
      item { ResultPlaceholder() }
    } else {
      banners(state = state, onRefresh = onRefresh)
      // A recording with no result and nothing queued or running has no result coming; the status
      // cards below say what it is, and a summary would contradict them.
      if (showsScoreCard(status = state.status, hasResult = processed)) {
        item {
          RecordingSummary(
              items = analysis?.items.orEmpty(),
              score =
                  (analysis?.let(::sleepScoreCardState) as? SleepScoreCardState.Scored)?.score,
              onOpenScore = { if (analysis != null) onOpenScore() },
          )
        }
      }
      // The choice is for a recording that has an analysis or is getting one. An upload in
      // progress, a failed upload and a recording without an analysis have a status card instead.
      if (showsAnalysisChoice(state)) {
        item { AnalysisChoiceButton(run = run, onOpenAnalysis = onOpenAnalysis) }
      }
      when {
        processed && analysis != null ->
            analysedRecording(analysis = analysis, state = state, onExpand = onExpand)
        !analysed ->
            statusCards(
                state = state,
                onUpload = onUpload,
                onRefresh = onRefresh,
                onRetryAnalysis = onRetryAnalysis,
                onLogin = onLogin,
                onOpenAnalysis = onOpenAnalysis,
            )
      }
    }
    item {
      RecordingSection(
          state = state,
          onExport = onExport,
          onDownloadRaw = onDownloadRaw,
          onDeleteRaw = onDeleteRaw,
          onDelete = onDelete,
      )
    }
  }
}

/** What the screen has to report about loading the recording and its result. */
private fun LazyListScope.banners(state: RecordingDetailUiState, onRefresh: () -> Unit) {
  state.error?.takeIf { !state.offlineSaved }?.let { message ->
    item {
      StatusBanner(
          kind = StatusKind.Warn,
          title = stringResource(R.string.night_load_failed),
          body = message,
          actions = {
            SecondaryButton(
                text = stringResource(R.string.night_retry),
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
            )
          },
      )
    }
  }
  if (state.offlineSaved) {
    item {
      StatusBanner(
          kind = StatusKind.Warn,
          body = stringResource(R.string.night_offline_saved),
      )
    }
  }
  state.actionError?.let { message ->
    item { StatusBanner(kind = StatusKind.Bad, body = message) }
  }
}

/** The four sections of a finished result, in the order the screen reads. */
private fun LazyListScope.analysedRecording(
    analysis: AnalysisResultDto,
    state: RecordingDetailUiState,
    onExpand: (String) -> Unit,
) {
  val heartItems = numberItems(analysis.items, "heart", "heart_support")
  val series = analysis.items.firstOrNull { it.kind == "series" && it.id == "hr" }?.series
  val movementItems = numberItems(analysis.items, "movement")
  val qualityItems = numberItems(analysis.items, "quality")
  item { SleepStatsSection(state = state, analysis = analysis, onExpand = { onExpand("sleep") }) }
  if (series != null || heartItems.isNotEmpty()) {
    item {
      HeartRateStatsSection(
          analysis = analysis,
          onExpand = { onExpand("heart") },
      )
    }
  }
  if (movementItems.isNotEmpty()) {
    item { MovementStatsSection(items = movementItems) }
  }
  if (analysis.items.any { it.id == "data_quality" } || qualityItems.isNotEmpty()) {
    item { DataQualityStatsSection(analysis = analysis) }
  }
}

/**
 * The layout of a result that is being read from this phone: neutral blocks where the figures go.
 * It carries no text, and a screen reader hears one description of it.
 */
@Composable
private fun ResultPlaceholder() {
  val description = stringResource(R.string.night_result_loading)
  val space = MaterialTheme.taiga.space
  Column(
      modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
      verticalArrangement = Arrangement.spacedBy(space.m),
  ) {
    TaigaCard {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.s)) {
          SkeletonBlock(width = 96.dp, height = 16.dp)
          SkeletonBlock(width = 190.dp, height = 12.dp)
        }
        SkeletonBlock(width = 96.dp, height = 96.dp, shape = CircleShape)
      }
    }
    TaigaCard {
      SkeletonBlock(width = 80.dp, height = 18.dp)
      Spacer(Modifier.height(space.m))
      SkeletonBlock(height = 128.dp)
      Spacer(Modifier.height(space.m))
      SkeletonBlock(width = 140.dp, height = 16.dp)
      Spacer(Modifier.height(space.s))
      SkeletonBlock(height = 48.dp)
    }
    TaigaCard {
      SkeletonBlock(width = 110.dp, height = 18.dp)
      Spacer(Modifier.height(space.m))
      SkeletonBlock(height = 96.dp)
    }
    TaigaCard {
      SkeletonBlock(width = 110.dp, height = 18.dp)
      Spacer(Modifier.height(space.m))
      SkeletonBlock(height = 16.dp)
      Spacer(Modifier.height(space.s))
      SkeletonBlock(height = 16.dp)
    }
    TaigaCard {
      SkeletonBlock(width = 96.dp, height = 18.dp)
      Spacer(Modifier.height(space.m))
      SkeletonBlock(height = 16.dp)
      Spacer(Modifier.height(space.s))
      SkeletonBlock(width = 150.dp, height = 16.dp)
    }
  }
}

/** One neutral block of a [ResultPlaceholder]. */
@Composable
private fun SkeletonBlock(
    width: Dp? = null,
    height: Dp,
    shape: Shape = MaterialTheme.taiga.shape.control,
) {
  Box(
      Modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
          .height(height)
          .clip(shape)
          .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
  )
}

