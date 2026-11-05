package com.rbmr.taigasleep.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagerPreferenceTest {
  @Test
  fun versionsCompareNumerically() {
    assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
    assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
    assertEquals(0, compareVersions("1.0.0", "1.0.0"))
  }
}
