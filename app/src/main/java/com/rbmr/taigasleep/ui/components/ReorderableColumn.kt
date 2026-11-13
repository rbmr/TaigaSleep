package com.rbmr.taigasleep.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.theme.taiga
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The slot a row dragged from [from] by [offset] pixels lands in, within a list of [count] rows. */
fun reorderTarget(from: Int, offset: Float, rowHeight: Float, count: Int): Int {
  if (count <= 0 || rowHeight <= 0f) return from
  return (from + (offset / rowHeight).roundToInt()).coerceIn(0, count - 1)
}

/** [list] with the item at [from] moved to [to]; out-of-range indexes leave it unchanged. */
fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
  if (from !in list.indices || to !in list.indices || from == to) return list
  return list.toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** The drag in progress: the order on screen, the row held and how far it is from its slot. */
private class DragState<T>(private val key: (T) -> Any, private val scope: CoroutineScope) {
  var live by mutableStateOf<List<T>>(emptyList())
  var dragged by mutableStateOf<Any?>(null)
  var rowHeight by mutableFloatStateOf(0f)

  /** How far the held row is from its slot. Plain state: every drag event reads the last one. */
  var offset by mutableFloatStateOf(0f)
    private set

  fun start(id: Any) {
    offset = 0f
    dragged = id
  }

  fun drag(id: Any, deltaY: Float) {
    val current = live.indexOfFirst { key(it) == id }
    if (current < 0) return
    val total = offset + deltaY
    val target = reorderTarget(current, total, rowHeight, live.size)
    live = moveItem(live, current, target)
    // The row now sits in a new slot, so the offset is relative to that slot.
    offset = total - (target - current) * rowHeight
  }

  fun drop(saved: List<T>, onReorder: (List<T>) -> Unit) {
    scope.launch {
      animate(offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { value, _ ->
        offset = value
      }
      dragged = null
      if (live != saved) onReorder(live)
    }
  }

  fun cancel(saved: List<T>) {
    scope.launch {
      animate(offset, 0f) { value, _ -> offset = value }
      dragged = null
      live = saved
    }
  }
}

/**
 * A list the user reorders by dragging the handle of a row; the other rows slide out of the way
 * and the dropped row settles into its slot. Screen readers get Move up and Move down actions on
 * every row instead. [row] draws one row and puts [dragHandle] where the handle belongs.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, index: Int, dragHandle: @Composable () -> Unit) -> Unit,
) {
  val scope = rememberCoroutineScope()
  // The drag gesture outlives recompositions, so it must read the latest list and callback.
  val latestItems by rememberUpdatedState(items)
  val latestOnReorder by rememberUpdatedState(onReorder)
  val state = remember { DragState(key, scope) }
  // The order shown while a drag is under way; the saved order changes once, on drop.
  if (state.dragged == null && state.live != items) state.live = items

  Column(modifier = modifier) {
    state.live.forEachIndexed { index, item ->
      val id = key(item)
      key(id) {
        ReorderableRow(
            state = state,
            id = id,
            index = index,
            lastIndex = items.lastIndex,
            onMove = { to -> onReorder(moveItem(items, index, to)) },
            onDrop = { state.drop(latestItems, latestOnReorder) },
            onCancel = { state.cancel(latestItems) },
        ) { handle ->
          row(item, index, handle)
        }
      }
    }
  }
}

@Composable
private fun <T> ReorderableRow(
    state: DragState<T>,
    id: Any,
    index: Int,
    lastIndex: Int,
    onMove: (Int) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    content: @Composable (dragHandle: @Composable () -> Unit) -> Unit,
) {
  val isDragged = state.dragged == id
  val slide = remember { Animatable(0f) }
  var shownIndex by remember { mutableIntStateOf(index) }
  // A row pushed aside by the dragged one starts where it was and glides to its new slot.
  LaunchedEffect(index) {
    if (index != shownIndex && !isDragged) {
      slide.snapTo((shownIndex - index) * state.rowHeight)
      slide.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
    }
    shownIndex = index
  }
  val moveUp = stringResource(R.string.reorder_move_up)
  val moveDown = stringResource(R.string.reorder_move_down)
  val lifted =
      if (isDragged) {
        Modifier.shadow(4.dp, MaterialTheme.taiga.shape.control)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.taiga.shape.control,
            )
      } else {
        Modifier
      }
  Box(
      modifier =
          Modifier.zIndex(if (isDragged) 1f else 0f)
              .onSizeChanged { if (isDragged || state.rowHeight == 0f) state.rowHeight = it.height.toFloat() }
              .graphicsLayer { translationY = if (isDragged) state.offset else slide.value }
              .then(lifted)
              .semantics {
                customActions =
                    buildList {
                      if (index > 0) {
                        add(CustomAccessibilityAction(moveUp) { onMove(index - 1); true })
                      }
                      if (index < lastIndex) {
                        add(CustomAccessibilityAction(moveDown) { onMove(index + 1); true })
                      }
                    }
              }
  ) {
    content {
      DragHandle(
          description = stringResource(R.string.reorder_drag_handle),
          modifier = Modifier.dragging(state, id, onDrop, onCancel),
      )
    }
  }
}

/** Reports the vertical drag of the handle to the list. */
private fun <T> Modifier.dragging(
    state: DragState<T>,
    id: Any,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
): Modifier =
    pointerInput(id) {
      // The gesture block is not restarted on recomposition: always call the current callbacks.
      detectDragGestures(
          onDragStart = { state.start(id) },
          onDrag = { change, amount ->
            change.consume()
            state.drag(id, amount.y)
          },
          onDragEnd = onDrop,
          onDragCancel = onCancel,
      )
    }

/** The grip at the end of a reorderable row, in a 48 dp touch target. */
@Composable
private fun DragHandle(description: String, modifier: Modifier = Modifier) {
  Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
    Icon(
        imageVector = Icons.Outlined.DragHandle,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
