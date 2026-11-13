package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.insights.AlgorithmNames
import com.rbmr.taigasleep.insights.stageLabelRes
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisOrigin
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.ui.components.ChoiceButton
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TertiaryButton
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt

/** The one button that says which analysis is shown and opens the choice. */
@Composable
internal fun AnalysisChoiceButton(run: AnalysisDto?, onOpenAnalysis: () -> Unit) {
  val name =
      run?.algorithm?.takeIf { it.isNotBlank() }?.let { algorithm ->
        val display = AlgorithmNames.display(algorithm)
        if (run.version.isNotBlank()) {
          stringResource(R.string.night_analysis_name_version, display, run.version)
        } else {
          display
        }
      }
  ChoiceButton(
      label =
          if (name != null) {
            stringResource(R.string.night_analyzed_with_algorithm, name)
          } else {
            stringResource(R.string.night_analysis_choose)
          },
      action = if (name != null) stringResource(R.string.night_analysis_change) else null,
      emphasis = name,
      onClick = onOpenAnalysis,
  )
}

/**
 * The analyses of this recording and the methods that can run on it next. The link that explains
 * how the methods differ is only there when the server describes at least one of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisSheet(
    runs: List<Analysis>,
    selectedId: String?,
    algorithms: List<AlgorithmDto>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
  val space = MaterialTheme.taiga.space
  val described = algorithms.filter { it.description.isNotBlank() }
  val differ = stringResource(R.string.night_analysis_differ)
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = space.m)
                .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.l),
    ) {
      Text(
          text = stringResource(R.string.night_analysis),
          style = MaterialTheme.taiga.text.screenTitle,
      )
      if (runs.isNotEmpty()) {
        Section(title = stringResource(R.string.night_analysis_done_heading)) {
          TaigaCard(contentPadding = PaddingValues(0.dp)) {
            runs.forEachIndexed { index, run ->
              if (index > 0) SettingsDivider()
              AnalysisRunRow(run = run, selectedId = selectedId, busy = busy, onSelect = onSelect)
            }
          }
        }
      }
      Section(
          title = stringResource(R.string.night_analysis_run_heading),
          info =
              if (described.isEmpty()) {
                null
              } else {
                InfoContent(differ, methodDescriptions(described))
              },
      ) {
        when {
          loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(space.s))
              Text(
                  text = stringResource(R.string.night_analysis_loading),
                  style = MaterialTheme.taiga.text.meta,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          error != null -> {
            Text(
                text = error,
                style = MaterialTheme.taiga.text.body,
                color = MaterialTheme.colorScheme.error,
            )
          }
          else -> {
            val existing = runs.map { it.algorithm to it.version }.toSet()
            val available = algorithms.filter { (it.id to it.version) !in existing }
            if (available.isEmpty()) {
              Text(
                  text = stringResource(R.string.night_analysis_empty),
                  style = MaterialTheme.taiga.text.body,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              TaigaCard(contentPadding = PaddingValues(0.dp)) {
                available.forEachIndexed { index, algorithm ->
                  if (index > 0) SettingsDivider()
                  AlgorithmRow(algorithm = algorithm, busy = busy, onRun = onRun)
                }
              }
            }
          }
        }
      }
      TertiaryButton(text = stringResource(R.string.night_sheet_close), onClick = onDismiss)
    }
  }
}

/** One line per installed method: its name and version, then the description from the server. */
@Composable
private fun methodDescriptions(algorithms: List<AlgorithmDto>): String {
  val lines =
      algorithms.map { algorithm ->
        stringResource(
            R.string.night_analysis_method_description,
            algorithmName(algorithm),
            algorithm.description,
        )
      }
  return lines.joinToString("\n\n")
}

@Composable
private fun algorithmName(algorithm: AlgorithmDto): String {
  val display = algorithm.name.ifBlank { AlgorithmNames.display(algorithm.id) }
  return if (algorithm.version.isNotBlank()) {
    stringResource(R.string.night_analysis_name_version, display, algorithm.version)
  } else {
    display
  }
}

/** One analysis of this recording: how it went, and the score it gives when it is done. */
@Composable
private fun AnalysisRunRow(
    run: Analysis,
    selectedId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
  val title =
      AlgorithmNames.display(run.algorithm.ifBlank { run.id }).let { displayName ->
        if (run.version.isNotBlank()) {
          stringResource(R.string.night_analysis_name_version, displayName, run.version)
        } else {
          displayName
        }
      }
  val manual = run.origin == AnalysisOrigin.MANUAL
  val trailing: ListRowTrailing =
      when (run.status) {
        AnalysisStatus.DONE ->
            ListRowTrailing.Radio(selected = run.id == selectedId)
        AnalysisStatus.FAILED ->
            ListRowTrailing.Pill(
                label = stringResource(R.string.night_analysis_status_failed),
                state = StatusPillState.Bad,
            )
        else ->
            ListRowTrailing.Pill(
                label = stringResource(R.string.night_analysis_analyzing),
                state = StatusPillState.Busy,
            )
      }
  val done = run.status == AnalysisStatus.DONE
  ListRow(
      title = title,
      subtitle = if (manual) stringResource(R.string.night_analysis_manual) else null,
      value =
          run.sleepScore?.takeIf { done }?.let {
            stringResource(R.string.night_analysis_score, it.roundToInt())
          },
      trailing = trailing,
      enabled = !busy,
      onClick = if (done) {
        { if (run.id != selectedId) onSelect(run.id) }
      } else {
        null
      },
  )
}

/** One method that can run on this recording next. */
@Composable
private fun AlgorithmRow(algorithm: AlgorithmDto, busy: Boolean, onRun: (String) -> Unit) {
  ListRow(
      title = algorithmName(algorithm),
      subtitle =
          if (algorithm.supported_stages.isEmpty()) {
            null
          } else {
            val stages = algorithm.supported_stages.map { stringResource(stageLabelRes(it)) }
            stringResource(
                R.string.night_analysis_stages,
                stages.joinToString(stringResource(R.string.night_analysis_list_separator)),
            )
          },
      trailing =
          ListRowTrailing.Action(
              label = stringResource(R.string.night_analysis_run),
              onClick = { onRun(algorithm.id) },
              enabled = !busy,
          ),
  )
}
