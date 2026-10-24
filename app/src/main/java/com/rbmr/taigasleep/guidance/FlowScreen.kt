package com.rbmr.taigasleep.guidance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rbmr.taigasleep.GuidanceStore
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SegmentedProgress
import com.rbmr.taigasleep.ui.components.StatusChip
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard

// Renders any [Flow]: an intro page, one page per step, then the summary.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    match: DeviceMatch,
    store: GuidanceStore,
    preview: Boolean,
    onClose: () -> Unit,
) {
  val context = LocalContext.current
  // The user can correct a wrong detection; the pick is remembered.
  var selectedId by remember { mutableStateOf(store.flowOverride) }
  val flow = GuidanceFlows.all.firstOrNull { it.id == selectedId } ?: match.flow
  val steps = flow.steps
  // Page 0 is the intro, 1..steps.size are the steps, the last page is the summary.
  var page by rememberSaveable { mutableIntStateOf(0) }
  val summaryPage = steps.size + 1
  val step = steps.getOrNull(page - 1)
  var resumes by remember { mutableIntStateOf(0) }
  val manual =
      remember(flow.id) {
        mutableStateMapOf<String, Boolean>().apply {
          steps.filter { it.check == null }.forEach { put(it.id, store.isDone(flow.id, it.id)) }
        }
      }

  // The user comes back from Settings, so read the device checks again on every resume.
  LifecycleResumeEffect(Unit) {
    resumes++
    onPauseOrDispose {}
  }
  val checks =
      remember(resumes, flow.id) {
        steps.mapNotNull { s -> s.check?.let { s.id to it(context) } }.toMap()
      }
  val isOk: (Step) -> Boolean = { Progress.isOk(checks[it.id], manual[it.id] == true) }

  BackHandler { if (page > 0) page-- else onClose() }

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        Column {
          TopAppBar(
              title = { Text(stringResource(R.string.g_flow_title)) },
              navigationIcon = {
                IconButton(onClick = onClose) {
                  Icon(
                      imageVector = Icons.Outlined.Close,
                      contentDescription = stringResource(R.string.g_close),
                  )
                }
              },
          )
          SegmentedProgress(
              steps = summaryPage + 1,
              current = page + 1,
              description = stringResource(R.string.g_progress, page + 1, summaryPage + 1),
              modifier = Modifier.padding(horizontal = 24.dp),
          )
        }
      },
      bottomBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .navigationBarsPadding()
                      .padding(horizontal = 24.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (page == 0) {
              PrimaryButton(
                  text = stringResource(R.string.g_start),
                  onClick = { page++ },
                  modifier = Modifier.weight(1f),
                  size = ButtonSize.Large,
              )
            } else {
              SecondaryButton(
                  text = stringResource(R.string.g_back),
                  onClick = { page-- },
                  modifier = Modifier.weight(1f),
                  size = ButtonSize.Large,
              )
              PrimaryButton(
                  text = stringResource(if (page == summaryPage) R.string.g_done else R.string.g_next),
                  onClick = { if (page == summaryPage) onClose() else page++ },
                  modifier = Modifier.weight(2f),
                  size = ButtonSize.Large,
              )
            }
          }
        }
      },
  ) { padding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when {
        page == 0 ->
            Intro(
                match = match,
                flow = flow,
                preview = preview,
                onPick = { picked ->
                  // Picking the detected flow again clears the correction.
                  val override = picked.id.takeIf { it != match.flow.id }
                  store.flowOverride = override
                  selectedId = override
                },
            )
        step != null ->
            StepPage(
                step = step,
                ok = isOk(step),
                onToggle = {
                  manual[step.id] = it
                  store.setDone(flow.id, step.id, it)
                },
            )
        else -> Summary(Progress.summary(flow, isOk), Progress.isReady(flow, isOk))
      }
    }
  }
}

@Composable
private fun Intro(match: DeviceMatch, flow: Flow, preview: Boolean, onPick: (Flow) -> Unit) {
  var picking by remember { mutableStateOf(false) }
  Text(
      text = stringResource(R.string.g_flow_title),
      style = MaterialTheme.typography.headlineMedium,
  )
  Text(
      text = stringResource(R.string.g_intro_body),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  // Shown only while the flow is the one the app picked on its own.
  val detected = match.fidelity != Fidelity.UNRECOGNIZED && flow.id == match.flow.id
  TaigaCard {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (detected) {
        Text(
            text = stringResource(R.string.g_detected_auto).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
      }
      Text(text = stringResource(flow.nameRes), style = MaterialTheme.typography.titleLarge)
      val note =
          when {
            match.fidelity == Fidelity.UNRECOGNIZED && flow.id == match.flow.id ->
                R.string.g_not_detected
            detected && match.fidelity == Fidelity.DERIVED -> R.string.g_derived_note
            else -> null
          }
      note?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (preview) {
        Text(
            text = stringResource(R.string.g_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          text = stringResource(R.string.g_not_my_os),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textDecoration = TextDecoration.Underline,
          modifier =
              Modifier.clickable(role = Role.Button) { picking = true }
                  .padding(top = 6.dp, bottom = 6.dp),
      )
    }
  }
  if (picking) {
    AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text(stringResource(R.string.g_pick_os_title)) },
        text = {
          Column {
            GuidanceFlows.all.forEach { option ->
              Row(
                  modifier =
                      Modifier.fillMaxWidth()
                          .clickable {
                            onPick(option)
                            picking = false
                          }
                          .padding(vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(selected = option.id == flow.id, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(option.nameRes))
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { picking = false }) { Text(stringResource(R.string.g_close)) }
        },
    )
  }
}

@Composable
private fun StepPage(step: Step, ok: Boolean, onToggle: (Boolean) -> Unit) {
  val context = LocalContext.current
  Text(
      text =
          stringResource(
                  if (step.kind == StepKind.REQUIRED) R.string.g_required else R.string.g_recommended
              )
              .uppercase(),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
  )
  Text(text = stringResource(step.titleRes), style = MaterialTheme.typography.headlineMedium)
  Text(
      text = stringResource(step.descriptionRes),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  TaigaCard {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      step.guideRes.forEach { res ->
        Row {
          Text(
              text = "•",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.width(20.dp),
          )
          Text(text = stringResource(res), style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
  step.helper?.let { helper ->
    PrimaryButton(
        text = stringResource(helper.labelRes),
        onClick = { GuidanceActions.open(context, helper.action) },
    )
  }
  // Manual steps: ticking the box flips the same OK / NOT YET state a device check would set.
  if (step.check == null) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(value = ok, role = Role.Checkbox, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = ok, onCheckedChange = null)
      Spacer(Modifier.width(12.dp))
      Text(
          text = stringResource(R.string.g_i_have_done_this),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(vertical = 14.dp),
      )
    }
  }
  StateBadge(ok, Modifier.fillMaxWidth())
}

// The OK / NOT YET state, the same chip for device checks and manual confirmation.
@Composable
private fun StateBadge(ok: Boolean, modifier: Modifier = Modifier) {
  StatusChip(
      kind = if (ok) StatusKind.Ok else StatusKind.Warn,
      label = stringResource(if (ok) R.string.g_state_ok else R.string.g_state_not_yet).uppercase(),
      modifier = modifier,
  )
}

@Composable
private fun Summary(lines: List<SummaryLine>, ready: Boolean) {
  Text(
      text = stringResource(if (ready) R.string.g_verdict_ready else R.string.g_verdict_not_ready),
      style = MaterialTheme.typography.headlineMedium,
  )
  lines.forEach { line ->
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(line.step.titleRes),
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(12.dp))
      StateBadge(line.ok)
    }
  }
}
