package com.rbmr.taigasleep.guidance

import android.content.Context
import com.rbmr.taigasleep.GuidanceStore

// One line of the summary page.
data class SummaryLine(
    val step: Step,
    val ok: Boolean,
)

// OK / NOT YET logic shared by the step pages, the summary and the Settings row.
object Progress {
  // A step is either checked on the device or confirmed by the user, never both.
  fun isOk(checkResult: Boolean?, manualDone: Boolean): Boolean = checkResult ?: manualDone

  fun isOk(context: Context, store: GuidanceStore, flow: Flow, step: Step): Boolean =
      isOk(step.check?.invoke(context), store.isDone(flow.id, step.id))

  fun summary(flow: Flow, isOk: (Step) -> Boolean): List<SummaryLine> =
      flow.steps.map { SummaryLine(it, isOk(it)) }

  // Ready means every required step is OK; recommended steps never block.
  fun isReady(flow: Flow, isOk: (Step) -> Boolean): Boolean =
      flow.steps.filter { it.kind == StepKind.REQUIRED }.all(isOk)

  fun isReady(context: Context, store: GuidanceStore, flow: Flow): Boolean =
      isReady(flow) { isOk(context, store, flow, it) }
}
