package com.rbmr.taigasleep.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class AlgorithmNamesTest {
  @Test
  fun theKnownAlgorithmsHaveNames() {
    assertEquals("Baseline HR/ACC", AlgorithmNames.display("baseline"))
    assertEquals("wav2sleep ECG", AlgorithmNames.display("wav2sleep"))
    assertEquals("SleepECG", AlgorithmNames.display("sleep-ecg"))
  }

  @Test
  fun anUnknownAlgorithmKeepsItsId() {
    assertEquals("my-model", AlgorithmNames.display("my-model"))
  }
}
