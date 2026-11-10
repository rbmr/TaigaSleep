package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.AnalysisResultDto
import com.rbmr.taigasleep.insights.MetricSpec
import com.rbmr.taigasleep.insights.sleepScoreCardState
import com.rbmr.taigasleep.library.formatNightHeader
import com.rbmr.taigasleep.library.formatRecordingDate
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard

/**
 * The scrolling body of the recording screen: the header, what the screen has to report, and the parts
 * that belong to the recording's state. Nothing here decides anything.
 */
@Composable
internal fun RecordingBody(
    state: RecordingDetailUiState,
    padding: PaddingValues,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    onRetryAnalysis: () -> Unit,
    onLogin: () -> Unit,
    onOpenAnalysis: () -> Unit,
    onOpenQuality: () -> Unit,
    onOpenSleepScore: () -> Unit,
    onExplainScore: () -> Unit,
    onMetric: (MetricSpec) -> Unit,
    onExpand: (String) -> Unit,
) {
  val run = state.result
  val analysis = run?.result
  val processed = run?.isSucceeded == true
  // Whether the night has a finished analysis is what the library knows, not whether its result
  // has been read yet. While that result is on its way the screen shows the layout it will fill.
  val analysed = state.analysed
  val reading = state.loading && analysed && analysis == null
  val headerLine =
      formatNightHeader(
          tracker = state.recordingTracker.ifBlank { stringResource(R.string.night_device_unknown) },
          startUtcMs = state.recordingStartUtcMs,
          endUtcMs = state.recordingEndUtcMs,
          lineFormat = stringResource(R.string.night_header_line),
          startOnlyFormat = stringResource(R.string.night_header_line_start_only),
      )

  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      ScreenHeader(
          kicker = stringResource(R.string.night_kicker),
          title = formatRecordingDate(state.start),
          subtitle = headerLine,
          modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
    if (reading) {
      item { ResultPlaceholder() }
    } else {
      item {
        RecordingHeaderCard(
            state = analysis?.let(::sleepScoreCardState),
            processed = analysed,
            onScoreClick = { if (run != null) onOpenSleepScore() },
            onExplainExperimental = onExplainScore,
        )
      }
      if (state.remoteId != null) {
        item {
          val model = run?.algorithm.orEmpty()
          val version = run?.version.orEmpty()
          val label =
              if (model.isNotBlank()) {
                stringResource(R.string.night_analyzed_with, model, version) +
                    " · " +
                    stringResource(R.string.night_analysis_change)
              } else {
                stringResource(R.string.night_analysis_choose)
              }
          Text(
              text = label,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 4.dp).clickable { onOpenAnalysis() },
          )
        }
      }

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
          Text(
              text = stringResource(R.string.night_offline_saved),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }
      state.actionError?.let { message ->
        item {
          Text(
              text = message,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }

      when {
        processed && analysis != null ->
            processedRecording(
                state = state,
                run = run,
                analysis = analysis,
                onMetric = onMetric,
                onExpand = onExpand,
                onOpenQuality = onOpenQuality,
            )
        !analysed ->
            statusCards(
                state = state,
                onUpload = onUpload,
                onRefresh = onRefresh,
                onRetryAnalysis = onRetryAnalysis,
                onLogin = onLogin,
            )
      }
    }
    item { Spacer(Modifier.height(12.dp)) }
  }
}

/** The cards of a recording whose result is here and can be shown. */
private fun LazyListScope.processedRecording(
    state: RecordingDetailUiState,
    run: AnalysisDto,
    analysis: AnalysisResultDto,
    onMetric: (MetricSpec) -> Unit,
    onExpand: (String) -> Unit,
    onOpenQuality: () -> Unit,
) {
  item {
    SleepStatsCard(
        state = state,
        analysis = analysis,
        onMetric = onMetric,
        onExpand = { onExpand("sleep") },
    )
  }
  val heartItems = analysis.items.filter { it.group == "heart" }
  val qualityItems = analysis.items.filter { it.group == "quality" }
  val movementItems = analysis.items.filter { it.group == "movement" }
  if (heartItems.any { it.kind == "series" || it.kind == "number" }) {
    item {
      HeartRateStatsCard(
          analysis = analysis,
          onMetric = onMetric,
          onExpand = { onExpand("heart") },
      )
    }
  }
  if (analysis.items.any { it.id == "data_quality" } || qualityItems.isNotEmpty()) {
    item { DataQualityStatsCard(analysis = analysis, onOpen = onOpenQuality, onMetric = onMetric) }
  }
  if (movementItems.isNotEmpty()) {
    item { MovementStatsCard(items = movementItems, onMetric = onMetric) }
  }
  analysis.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
    item { WarningCard(warnings) }
  }
  run.algorithm.takeIf { it.isNotBlank() }?.let { model ->
    item {
      Text(
          text = stringResource(R.string.night_analyzed_with, model, run.version),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
  }
}

@Composable
private fun WarningCard(warnings: List<String>) {
  TaigaCard {
    Text(
        text = stringResource(R.string.night_warnings),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(8.dp))
    warnings.forEach {
      Text(
          text = "• $it",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * The layout of a result that is being read from this phone: the same cards, with neutral blocks
 * where the figures go. It carries no text, and a screen reader hears one description of it.
 */
@Composable
private fun ResultPlaceholder() {
  val description = stringResource(R.string.night_result_loading)
  Column(
      modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    TaigaCard {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          SkeletonBlock(width = 96.dp, height = 16.dp)
          SkeletonBlock(width = 190.dp, height = 12.dp)
        }
        SkeletonBlock(width = 96.dp, height = 96.dp, shape = RoundedCornerShape(50))
      }
    }
    TaigaCard {
      SkeletonBlock(width = 80.dp, height = 18.dp)
      Spacer(Modifier.height(14.dp))
      SkeletonBlock(height = 128.dp)
      Spacer(Modifier.height(16.dp))
      SkeletonBlock(width = 140.dp, height = 16.dp)
      Spacer(Modifier.height(10.dp))
      SkeletonBlock(height = 48.dp)
    }
    TaigaCard {
      SkeletonBlock(width = 110.dp, height = 18.dp)
      Spacer(Modifier.height(14.dp))
      SkeletonBlock(height = 96.dp)
    }
    TaigaCard {
      SkeletonBlock(width = 110.dp, height = 18.dp)
      Spacer(Modifier.height(14.dp))
      SkeletonBlock(height = 16.dp)
      Spacer(Modifier.height(10.dp))
      SkeletonBlock(height = 16.dp)
    }
    TaigaCard {
      SkeletonBlock(width = 96.dp, height = 18.dp)
      Spacer(Modifier.height(14.dp))
      SkeletonBlock(height = 16.dp)
      Spacer(Modifier.height(10.dp))
      SkeletonBlock(width = 150.dp, height = 16.dp)
    }
  }
}

/** One neutral block of a [ResultPlaceholder]. */
@Composable
private fun SkeletonBlock(
    width: Dp? = null,
    height: Dp,
    shape: Shape = RoundedCornerShape(8.dp),
) {
  Box(
      Modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
          .height(height)
          .clip(shape)
          .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
  )
}
