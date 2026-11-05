package com.rbmr.taigasleep.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.library.Analysis
import com.rbmr.taigasleep.library.AnalysisOrigin
import com.rbmr.taigasleep.library.AnalysisStatus
import com.rbmr.taigasleep.ui.theme.taiga

/** Which result of this night is shown, and which other algorithm can be asked for. */
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
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = stringResource(R.string.night_analysis),
          style = MaterialTheme.typography.headlineSmall,
      )
      if (runs.isNotEmpty()) {
        Text(
            text = stringResource(R.string.night_analysis_this_night),
            style = MaterialTheme.typography.titleMedium,
        )
        runs.forEach { run ->
          AnalysisRow(run = run, selectedId = selectedId, busy = busy, onSelect = onSelect)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
      Text(
          text = stringResource(R.string.night_analysis_add),
          style = MaterialTheme.typography.titleMedium,
      )
      when {
        loading -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.night_analysis_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        error != null -> {
          Text(
              text = error,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
          )
        }
        else -> {
          val existing = runs.map { it.algorithm to it.version }.toSet()
          val available = algorithms.filter { (it.id to it.version) !in existing }
          if (available.isEmpty()) {
            Text(
                text = stringResource(R.string.night_analysis_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            available.forEach { algorithm ->
              AlgorithmRow(algorithm = algorithm, busy = busy, onRun = onRun)
            }
          }
        }
      }
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.night_sheet_close))
      }
    }
  }
}

@Composable
private fun AnalysisRow(
    run: Analysis,
    selectedId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(
          text =
              buildString {
                append(run.algorithm.ifBlank { run.id })
                if (run.version.isNotBlank()) {
                  append(" ")
                  append(run.version)
                }
                if (run.origin == AnalysisOrigin.MANUAL) append(" · Manual")
              },
          style = MaterialTheme.typography.titleSmall,
      )
      Text(
          text = analysisStatus(run.status),
          style = MaterialTheme.typography.bodySmall,
          color =
              when (run.status) {
                AnalysisStatus.DONE -> MaterialTheme.taiga.ok
                AnalysisStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
      )
    }
    if (run.status == AnalysisStatus.DONE) {
      if (run.id == selectedId) {
        Text(
            text = stringResource(R.string.night_analysis_shown),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.taiga.ok,
        )
      } else {
        TextButton(onClick = { onSelect(run.id) }, enabled = !busy) {
          Text(stringResource(R.string.night_analysis_show))
        }
      }
    }
  }
}

@Composable
private fun AlgorithmRow(algorithm: AlgorithmDto, busy: Boolean, onRun: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
            text = algorithm.name.ifBlank { algorithm.id },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "v${algorithm.version}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { onRun(algorithm.id) }, enabled = !busy) {
        Text(stringResource(R.string.night_analysis_run))
      }
    }
    if (algorithm.description.isNotBlank()) {
      Text(
          text = algorithm.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (algorithm.supported_stages.isNotEmpty()) {
      Text(
          text =
              stringResource(
                  R.string.night_analysis_stages,
                  algorithm.supported_stages.joinToString(", "),
              ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun analysisStatus(status: AnalysisStatus): String =
    stringResource(
        when (status) {
          AnalysisStatus.QUEUED -> R.string.night_analysis_status_queued
          AnalysisStatus.RUNNING -> R.string.night_analysis_status_running
          AnalysisStatus.DONE -> R.string.night_analysis_status_done
          AnalysisStatus.FAILED -> R.string.night_analysis_status_failed
        }
    )
