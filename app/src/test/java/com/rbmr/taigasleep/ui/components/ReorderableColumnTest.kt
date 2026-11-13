package com.rbmr.taigasleep.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderableColumnTest {
  @Test
  fun `a drag lands in the slot nearest to the dragged row`() {
    assertEquals(1, reorderTarget(from = 1, offset = 0f, rowHeight = 100f, count = 4))
    assertEquals(1, reorderTarget(from = 1, offset = 40f, rowHeight = 100f, count = 4))
    assertEquals(2, reorderTarget(from = 1, offset = 60f, rowHeight = 100f, count = 4))
    assertEquals(0, reorderTarget(from = 1, offset = -60f, rowHeight = 100f, count = 4))
    assertEquals(3, reorderTarget(from = 1, offset = 220f, rowHeight = 100f, count = 4))
  }

  @Test
  fun `dragging down moves the first and a middle row to the last slot`() {
    val order = listOf("a", "b", "c", "d")
    val first = reorderTarget(from = 0, offset = 320f, rowHeight = 100f, count = 4)
    val middle = reorderTarget(from = 1, offset = 210f, rowHeight = 100f, count = 4)

    assertEquals(3, first)
    assertEquals(listOf("b", "c", "d", "a"), moveItem(order, 0, first))
    assertEquals(3, middle)
    assertEquals(listOf("a", "c", "d", "b"), moveItem(order, 1, middle))
  }

  @Test
  fun `dragging down one row at a time lands each row in turn`() {
    var order = listOf("a", "b", "c")
    var offset = 0f
    // A held row dragged in small steps: after each step the offset is relative to its new slot.
    repeat(25) {
      val current = order.indexOf("a")
      val total = offset + 10f
      val target = reorderTarget(current, total, rowHeight = 100f, count = 3)
      order = moveItem(order, current, target)
      offset = total - (target - current) * 100f
    }

    assertEquals(listOf("b", "c", "a"), order)
  }

  @Test
  fun `a drag stops at the first and the last row`() {
    assertEquals(0, reorderTarget(from = 1, offset = -900f, rowHeight = 100f, count = 4))
    assertEquals(3, reorderTarget(from = 1, offset = 900f, rowHeight = 100f, count = 4))
  }

  @Test
  fun `an unmeasured or empty list keeps the row where it is`() {
    assertEquals(2, reorderTarget(from = 2, offset = 500f, rowHeight = 0f, count = 4))
    assertEquals(0, reorderTarget(from = 0, offset = 500f, rowHeight = 100f, count = 0))
  }

  @Test
  fun `moving an item shifts the rows between and keeps the rest`() {
    val order = listOf("a", "b", "c", "d")

    assertEquals(listOf("b", "c", "a", "d"), moveItem(order, 0, 2))
    assertEquals(listOf("a", "d", "b", "c"), moveItem(order, 3, 1))
    assertEquals(listOf("b", "a", "c", "d"), moveItem(order, 1, 0))
  }

  @Test
  fun `moving to the same or an unknown slot changes nothing`() {
    val order = listOf("a", "b", "c")

    assertEquals(order, moveItem(order, 1, 1))
    assertEquals(order, moveItem(order, 0, -1))
    assertEquals(order, moveItem(order, 2, 3))
    assertEquals(order, moveItem(order, 5, 0))
  }
}
