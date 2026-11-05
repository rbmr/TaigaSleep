package com.rbmr.taigasleep.guard

import com.rbmr.taigasleep.tracker.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
  @Test
  fun onDeviceRecordingDoesNotNeedTheBatteryFlow() {
    assertEquals(
        HomeState.NOT_NEEDED,
        ReadinessState.state(GenericFlow, RecordingMode.ON_DEVICE) { false },
    )
    assertEquals(
        HomeState.NEEDS_SETTINGS,
        ReadinessState.state(GenericFlow, RecordingMode.STREAM) { false },
    )
  }

  private val good =
      Readiness(
          ignoringBatteryOptimizations = true,
          standbyBucket = 5,
          backgroundRestricted = false,
      )

  @Test
  fun batteryExemptedFollowsTheWhitelistOnly() {
    assertTrue(good.copy(standbyBucket = 10).batteryExempted)
    assertFalse(good.copy(ignoringBatteryOptimizations = false).batteryExempted)
  }

  @Test
  fun backgroundAllowedNeedsAllThreeFacts() {
    assertTrue(good.backgroundAllowed)
    assertFalse(good.copy(ignoringBatteryOptimizations = false).backgroundAllowed)
    listOf(0, 10, 20, 30, 40, null).forEach {
      assertFalse("bucket $it", good.copy(standbyBucket = it).backgroundAllowed)
    }
    assertFalse(good.copy(backgroundRestricted = true).backgroundAllowed)
  }

  @Test
  fun noBucketReturnedIsNotReady() {
    // UsageStatsManager can answer with null; that is not evidence of a healthy bucket.
    assertFalse(good.copy(standbyBucket = null).backgroundAllowed)
  }

  @Test
  fun homeStateFollowsTheFlowsRequiredSteps() {
    GuidanceFlows.all.forEach { flow ->
      assertEquals(HomeState.READY, ReadinessState.state(flow) { true })
      assertEquals(HomeState.NEEDS_SETTINGS, ReadinessState.state(flow) { false })
      // Recommended steps never block, and every flow has at least one required step.
      assertEquals(
          HomeState.READY,
          ReadinessState.state(flow) { it.kind == StepKind.REQUIRED },
      )
      assertEquals(
          HomeState.NEEDS_SETTINGS,
          ReadinessState.state(flow) { it.kind == StepKind.RECOMMENDED },
      )
    }
  }

  @Test
  fun homeStateIsTheFlowVerdict() {
    // One result, two consumers: Home's card and the flow's summary page cannot disagree.
    GuidanceFlows.all.forEach { flow ->
      val predicates = listOf<(Step) -> Boolean>(
          { true },
          { false },
          { step -> step.id == flow.steps.first().id },
      )
      predicates.forEach { predicate ->
        val card = ReadinessState.state(flow, predicate)
        val verdict = Progress.isReady(flow, predicate)
        assertEquals(verdict, card == HomeState.READY)
      }
    }
  }

  @Test
  fun stricterFlowsCanNeedSettingsWhereTheGenericFlowDoesNot() {
    // Whitelisted, but the vendor power mode left the app in a non-exempted bucket.
    val whitelistedOnly = good.copy(standbyBucket = 10)
    fun stateOf(flow: Flow) =
        ReadinessState.state(flow) { step -> step.check?.invoke(whitelistedOnly) ?: true }

    assertEquals(HomeState.READY, stateOf(GenericFlow))
    assertEquals(HomeState.NEEDS_SETTINGS, stateOf(OnePlusFlow))
    assertEquals(HomeState.NEEDS_SETTINGS, stateOf(SamsungFlow))
  }

  @Test
  fun thePopupAloneSatisfiesTheGenericFlow() {
    val afterPopup = good
    assertTrue(afterPopup.batteryExempted)
    assertTrue(afterPopup.backgroundAllowed)
  }

  @Test
  fun summaryNamesEveryFact() {
    val text = good.summary()
    assertTrue(text, text.contains("ignoring_battery_optimizations=true"))
    assertTrue(text, text.contains("standby_bucket=5"))
    assertTrue(text, text.contains("background_restricted=false"))
  }
}
