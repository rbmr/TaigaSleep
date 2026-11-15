package com.rbmr.taigasleep.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

/**
 * Reads values off a chart with the finger: touching the plot selects the column under it and
 * dragging moves the selection. A drag that turns out to be mostly horizontal belongs to the chart
 * and is consumed, so a page that scrolls or swaps sideways stays where it is while the user reads.
 * A drag that is mostly vertical is left to the page, which scrolls under the chart as it always has.
 *
 * The direction is decided once, at the touch slop where a parent would start scrolling too. A
 * finger that never passes it is a tap and only selects.
 *
 * [accept] decides whether a touch belongs to the chart at all, such as one that starts beside the
 * plot on the axis labels. [onSelect] gets the x of the finger, [onClear] runs when it is gone.
 */
internal suspend fun PointerInputScope.scrubChart(
    onSelect: (Float) -> Unit,
    onClear: () -> Unit,
    accept: (Float) -> Boolean = { true },
) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!accept(down.position.x)) return@awaitEachGesture
    onSelect(down.position.x)
    var horizontal: Boolean? = null
    val slop = viewConfiguration.touchSlop
    var event = awaitPointerEvent()
    while (event.changes.any { it.pressed }) {
      val change = event.changes.lastOrNull { it.id == down.id } ?: event.changes.last()
      onSelect(change.position.x)
      if (horizontal == null) {
        val dx = change.position.x - down.position.x
        val dy = change.position.y - down.position.y
        if (dx * dx + dy * dy > slop * slop) horizontal = abs(dx) > abs(dy)
      }
      if (horizontal == true) event.changes.forEach { it.consume() }
      event = awaitPointerEvent()
    }
    onClear()
  }
}
