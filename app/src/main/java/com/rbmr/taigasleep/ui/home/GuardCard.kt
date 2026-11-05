package com.rbmr.taigasleep.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.guard.HomeState
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind

/**
 * The one place Home surfaces preventing shutdown: a single button in three states, always opening
 * the flow, which owns the instructions and the fixes.
 */
@Composable
internal fun GuardCard(guard: HomeState, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
  val ready = guard == HomeState.READY
  val notNeeded = guard == HomeState.NOT_NEEDED
  StatusBanner(
      kind = if (ready || notNeeded) StatusKind.Ok else StatusKind.Warn,
      title =
          stringResource(
              when {
                notNeeded -> R.string.rec_guard_not_needed_title
                ready -> R.string.rec_ready_title
                else -> R.string.rec_not_ready_title
              }
          ),
      body =
          stringResource(
              when {
                notNeeded -> R.string.rec_guard_not_needed_body
                ready -> R.string.rec_ready_body
                else -> R.string.rec_not_ready_body
              }
          ),
      onClick = onClick,
      onLongClick = onLongClick,
  )
}
