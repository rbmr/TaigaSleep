package com.rbmr.taigasleep.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinPartsTest {
  @Test
  fun `two parts are separated by a centre dot with a space on each side`() {
    assertEquals("Ends in 4f2a · Created 3 Nov 2026", joinParts("Ends in 4f2a", "Created 3 Nov 2026"))
  }

  @Test
  fun `a missing or blank part leaves no separator behind`() {
    assertEquals("Ends in 4f2a", joinParts("Ends in 4f2a", null))
    assertEquals("Ends in 4f2a", joinParts("Ends in 4f2a", ""))
    assertEquals("Created 3 Nov 2026", joinParts(null, "Created 3 Nov 2026"))
  }

  @Test
  fun `no part at all gives nothing to show`() {
    assertNull(joinParts(null, " "))
  }
}
