package com.rbmr.taigasleep.guidance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessCheckerTest {
  private val good =
      ReadinessInputs(
          ignoringBatteryOptimizations = true,
          standbyBucket = 5,
          backgroundRestricted = false,
      )

  @Test
  fun allowedWhenWhitelistedExemptedAndNotRestricted() {
    assertTrue(ReadinessChecker.backgroundAllowed(good))
  }

  @Test
  fun notWhitelistedBlocks() {
    assertFalse(ReadinessChecker.backgroundAllowed(good.copy(ignoringBatteryOptimizations = false)))
  }

  @Test
  fun bucketOtherThanExemptedBlocks() {
    listOf(10, 20, 30, 40, 45, 50, null).forEach {
      assertFalse("bucket $it", ReadinessChecker.backgroundAllowed(good.copy(standbyBucket = it)))
    }
  }

  @Test
  fun backgroundRestrictedBlocks() {
    assertFalse(ReadinessChecker.backgroundAllowed(good.copy(backgroundRestricted = true)))
  }

  @Test
  fun oneplusSmartModeIsNotAllowed() {
    // Smart or Restrict: whitelist removed, bucket ACTIVE.
    assertFalse(
        ReadinessChecker.backgroundAllowed(
            good.copy(ignoringBatteryOptimizations = false, standbyBucket = 10)
        )
    )
  }

  @Test
  fun batteryExemptionOnlyLooksAtTheWhitelist() {
    assertTrue(ReadinessChecker.batteryExempted(good.copy(standbyBucket = 10)))
    assertFalse(ReadinessChecker.batteryExempted(good.copy(ignoringBatteryOptimizations = false)))
  }
}
