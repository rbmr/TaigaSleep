package com.rbmr.taigasleep.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class ExporterTest {
  @Test
  fun rawOnThePhoneExportsWithoutTheServer() {
    assertEquals(ExportSource.LOCAL, exportSource(hasRaw = true, onServer = false))
    assertEquals(ExportSource.LOCAL, exportSource(hasRaw = true, onServer = true))
  }

  @Test
  fun missingRawIsFetchedFromTheServerFirst() {
    assertEquals(ExportSource.RESTORE, exportSource(hasRaw = false, onServer = true))
  }

  @Test
  fun aNightWithNoCopyCannotBeExported() {
    assertEquals(ExportSource.UNAVAILABLE, exportSource(hasRaw = false, onServer = false))
  }
}
