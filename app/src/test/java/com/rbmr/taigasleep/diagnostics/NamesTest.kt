package com.rbmr.taigasleep.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamesTest {
  @Test
  fun knownCodes() {
    assertEquals("OTHER", Names.exitReason(13))
    assertEquals("FOREGROUND_SERVICE", Names.importance(125))
    assertEquals("EXEMPTED", Names.standbyBucket(5))
    assertEquals("RESTRICTED", Names.standbyBucket(45))
    assertEquals("UI_HIDDEN", Names.trimLevel(20))
  }

  @Test
  fun unknownCodesKeepTheirNumber() {
    assertEquals("REASON_99", Names.exitReason(99))
    assertEquals("BUCKET_7", Names.standbyBucket(7))
  }

  @Test
  fun onlyCrashesAndAnrsHaveTraces() {
    assertTrue(Names.hasTrace(6))
    assertTrue(Names.hasTrace(5))
    assertFalse(Names.hasTrace(13))
  }
}
