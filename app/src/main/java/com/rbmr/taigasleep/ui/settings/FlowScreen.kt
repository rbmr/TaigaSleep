package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.guard.DeviceMatch
import com.rbmr.taigasleep.guard.Fidelity
import com.rbmr.taigasleep.guard.Flow
import com.rbmr.taigasleep.guard.GuidanceActions
import com.rbmr.taigasleep.guard.GuidanceFlows
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.guard.Progress
import com.rbmr.taigasleep.guard.Readiness
import com.rbmr.taigasleep.guard.Step
import com.rbmr.taigasleep.guard.StepKind
import com.rbmr.taigasleep.guard.SummaryLine
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Pressable
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SegmentedProgress
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusPill
import com.rbmr.taigasleep.ui.components.StatusPillState
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga

// Renders any [Flow]: an intro page, one page per step, then the summary.
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
  val readiness = remember(resumes) { Readiness.read(context) }
  val isOk: (Step) -> Boolean = { Progress.isOk(readiness, { manual[it.id] == true }, it) }
  val space = MaterialTheme.taiga.space

  // Back goes one page back, and leaves the flow from the intro.
  fun leave() {
    if (page > 0) page-- else onClose()
  }
  BackHandler { leave() }

  TaigaScreen(
      title = stringResource(R.string.g_flow_title),
      onBack = { leave() },
      scrollable = false,
      actions = {
        Pressable(
            onClick = onClose,
            shape = CircleShape,
            role = Role.Button,
            onClickLabel = stringResource(R.string.g_close),
        ) {
          Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.g_close),
            )
          }
        }
      },
  ) {
    SegmentedProgress(
        steps = summaryPage + 1,
        current = page + 1,
        description = stringResource(R.string.g_progress, page + 1, summaryPage + 1),
        modifier = Modifier.fillMaxWidth(),
    )
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space.m),
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = space.l),
        horizontalArrangement = Arrangement.spacedBy(space.s),
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
}

@Composable
private fun Intro(match: DeviceMatch, flow: Flow, preview: Boolean, onPick: (Flow) -> Unit) {
  var picking by remember { mutableStateOf(false) }
  Text(
      text = stringResource(R.string.g_intro_body),
      style = MaterialTheme.taiga.text.body,
  )
  // Shown only while the flow is the one the app picked on its own.
  val detected = match.fidelity != Fidelity.UNRECOGNIZED && flow.id == match.flow.id
  TaigaCard {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s)) {
      if (detected) {
        StatusPill(
            state = StatusPillState.Neutral,
            label = stringResource(R.string.g_detected_auto),
        )
      }
      Text(text = stringResource(flow.nameRes), style = MaterialTheme.taiga.text.section)
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
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (preview) {
        Text(
            text = stringResource(R.string.g_preview_note),
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
  SecondaryButton(
      text = stringResource(R.string.g_not_my_os),
      onClick = { picking = true },
      modifier = Modifier.fillMaxWidth(),
  )
  if (picking) {
    AlertDialog(
        onDismissRequest = { picking = false },
        title = {
          Text(
              text = stringResource(R.string.g_pick_os_title),
              style = MaterialTheme.taiga.text.section,
          )
        },
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
                          .padding(vertical = MaterialTheme.taiga.space.s),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                RadioButton(selected = option.id == flow.id, onClick = null)
                Spacer(Modifier.width(MaterialTheme.taiga.space.s))
                Text(text = stringResource(option.nameRes), style = MaterialTheme.taiga.text.body)
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
  StatusPill(
      state = StatusPillState.Neutral,
      label =
          stringResource(
              if (step.kind == StepKind.REQUIRED) R.string.g_required else R.string.g_recommended
          ),
  )
  Text(text = stringResource(step.titleRes), style = MaterialTheme.taiga.text.section)
  Text(
      text = stringResource(step.descriptionRes),
      style = MaterialTheme.taiga.text.body,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  if (step.guideRes.isNotEmpty()) {
    TaigaCard {
      Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.m)) {
        step.guideRes.forEach { res ->
          Row {
            Text(
                text = "•",
                style = MaterialTheme.taiga.text.row,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(20.dp),
            )
            Text(text = stringResource(res), style = MaterialTheme.taiga.text.body)
          }
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
  // Manual steps are answered by the user; a device check reports its own state.
  if (step.check == null) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s)) {
      AnswerButton(
          text = stringResource(R.string.g_state_ok),
          selected = ok,
          onClick = { onToggle(true) },
          modifier = Modifier.weight(1f),
      )
      AnswerButton(
          text = stringResource(R.string.g_state_not_yet),
          selected = !ok,
          onClick = { onToggle(false) },
          modifier = Modifier.weight(1f),
      )
    }
  } else {
    StatePill(ok)
  }
}

/** One of the two answers of a manual step; the chosen one is filled. */
@Composable
private fun AnswerButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  if (selected) {
    PrimaryButton(text = text, onClick = onClick, modifier = modifier, size = ButtonSize.Large)
  } else {
    SecondaryButton(text = text, onClick = onClick, modifier = modifier, size = ButtonSize.Large)
  }
}

// The OK / NOT YET state, the same pill for device checks and manual confirmation.
@Composable
private fun StatePill(ok: Boolean, modifier: Modifier = Modifier) {
  StatusPill(
      state = if (ok) StatusPillState.Ok else StatusPillState.Warn,
      label = stringResource(if (ok) R.string.g_state_ok else R.string.g_state_not_yet),
      modifier = modifier,
  )
}

@Composable
private fun Summary(lines: List<SummaryLine>, ready: Boolean) {
  Text(
      text = stringResource(if (ready) R.string.g_verdict_ready else R.string.g_verdict_not_ready),
      style = MaterialTheme.taiga.text.section,
  )
  TaigaCard(contentPadding = PaddingValues(0.dp)) {
    lines.forEachIndexed { index, line ->
      if (index > 0) SettingsDivider()
      ListRow(
          title = stringResource(line.step.titleRes),
          trailing =
              ListRowTrailing.Pill(
                  label =
                      stringResource(
                          if (line.ok) R.string.g_state_ok else R.string.g_state_not_yet
                      ),
                  state = if (line.ok) StatusPillState.Ok else StatusPillState.Warn,
              ),
      )
    }
  }
}
