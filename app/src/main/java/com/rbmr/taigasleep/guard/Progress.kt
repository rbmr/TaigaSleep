package com.rbmr.taigasleep.guard


// One line of the summary page.
data class SummaryLine(
    val step: Step,
    val ok: Boolean,
)

// OK / NOT YET logic shared by the step pages, the summary, Home and the recording service.
object Progress {
  // A step is either checked on the device or confirmed by the user, never both.
  fun isOk(checkResult: Boolean?, manualDone: Boolean): Boolean = checkResult ?: manualDone

  // [isDone] answers the manual steps; a step with a [Step.check] never asks it.
  fun isOk(readiness: Readiness, isDone: (Step) -> Boolean, step: Step): Boolean =
      isOk(step.check?.invoke(readiness), isDone(step))

  fun isOk(readiness: Readiness, store: GuidanceStore, flow: Flow, step: Step): Boolean =
      isOk(readiness, { store.isDone(flow.id, it.id) }, step)

  fun summary(flow: Flow, isOk: (Step) -> Boolean): List<SummaryLine> =
      flow.steps.map { SummaryLine(it, isOk(it)) }

  // Ready means every required step is OK; recommended steps never block.
  fun isReady(flow: Flow, isOk: (Step) -> Boolean): Boolean =
      flow.steps.filter { it.kind == StepKind.REQUIRED }.all(isOk)

  fun isReady(readiness: Readiness, store: GuidanceStore, flow: Flow): Boolean =
      isReady(flow) { isOk(readiness, store, flow, it) }
}
