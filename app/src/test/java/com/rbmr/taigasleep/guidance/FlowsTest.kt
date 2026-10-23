package com.rbmr.taigasleep.guidance

import com.rbmr.taigasleep.GuidanceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowsTest {
  private fun id(manufacturer: String?, brand: String?) =
      GuidanceFlows.flowFor(manufacturer, brand).id

  @Test
  fun oneplusFamilyMapsToOneplus() {
    assertEquals("oneplus", id("OnePlus", "OnePlus"))
    assertEquals("oneplus", id("ONEPLUS", null))
    assertEquals("oneplus", id("OPPO", "OPPO"))
    assertEquals("oneplus", id("realme", "realme"))
    assertEquals("oneplus", id(" Realme ", "RMX"))
    assertEquals("oneplus", id("Unknown", "OnePlus"))
  }

  @Test
  fun samsungMapsToSamsung() {
    assertEquals("samsung", id("samsung", "samsung"))
    assertEquals("samsung", id("Samsung", null))
  }

  @Test
  fun unknownBrandsFallBackToGeneric() {
    assertEquals("generic", id("Xiaomi", "Redmi"))
    assertEquals("generic", id("Google", "google"))
    assertEquals("generic", id("", ""))
    assertEquals("generic", id(null, null))
  }

  @Test
  fun fidelityReflectsWhatWasTested() {
    assertEquals(Fidelity.VERIFIED, GuidanceFlows.matchFor("OnePlus", "OnePlus", "CPH2769").fidelity)
    assertEquals(Fidelity.VERIFIED, GuidanceFlows.matchFor("samsung", "samsung", "SM-G991B").fidelity)
    assertEquals(Fidelity.DERIVED, GuidanceFlows.matchFor("OPPO", "OPPO", "CPH2557").fidelity)
    assertEquals(Fidelity.DERIVED, GuidanceFlows.matchFor("realme", "realme", "RMX3").fidelity)
    val unknown = GuidanceFlows.matchFor("Xiaomi", "Xiaomi", "23127PC0AG")
    assertEquals(Fidelity.UNRECOGNIZED, unknown.fidelity)
    assertEquals("generic", unknown.flow.id)
    assertEquals("Xiaomi 23127PC0AG", unknown.deviceLabel)
  }

  @Test
  fun everyPreviewDeviceResolves() {
    PreviewDevice.entries.forEach { assertTrue(GuidanceFlows.matchFor(it).flow.steps.isNotEmpty()) }
    assertEquals("oneplus", GuidanceFlows.matchFor(PreviewDevice.OPPO).flow.id)
    assertEquals("generic", GuidanceFlows.matchFor(PreviewDevice.UNKNOWN).flow.id)
  }

  @Test
  fun stepIdsAreUniqueAndGuidesShort() {
    GuidanceFlows.all.forEach { flow ->
      val ids = flow.steps.map { it.id }
      assertEquals(flow.id, ids.distinct(), ids)
      flow.steps.forEach { assertTrue("${flow.id}/${it.id}", it.guideRes.size in 1..4) }
    }
  }

  @Test
  fun everyFlowHasARequiredCheckedStep() {
    GuidanceFlows.all.forEach { flow ->
      val required = flow.steps.filter { it.kind == StepKind.REQUIRED }
      assertTrue(flow.id, required.isNotEmpty())
      // The required setting is read from the device, never left to a manual tick.
      required.forEach { assertNotNull("${flow.id}/${it.id}", it.check) }
    }
  }

  @Test
  fun requiredAndManualStepsPerFlow() {
    fun ids(flow: Flow, kind: StepKind) = flow.steps.filter { it.kind == kind }.map { it.id }
    assertEquals(listOf("background"), ids(OnePlusFlow, StepKind.REQUIRED))
    assertEquals(listOf("sleep_standby", "recents", "charge"), ids(OnePlusFlow, StepKind.RECOMMENDED))
    assertEquals(listOf("unrestricted"), ids(SamsungFlow, StepKind.REQUIRED))
    assertEquals(
        listOf("sleeping_apps", "keep_open", "charge"),
        ids(SamsungFlow, StepKind.RECOMMENDED),
    )
    assertEquals(listOf("exemption"), ids(GenericFlow, StepKind.REQUIRED))
    assertEquals(listOf("recents", "charge"), ids(GenericFlow, StepKind.RECOMMENDED))
  }

  @Test
  fun helpersMatchTheSteps() {
    assertEquals(
        GuidanceAction.REQUEST_EXEMPTION,
        GenericFlow.steps.first { it.id == "exemption" }.helper?.action,
    )
    assertEquals(
        GuidanceAction.APP_DETAILS,
        OnePlusFlow.steps.first { it.id == "background" }.helper?.action,
    )
    assertNull(OnePlusFlow.steps.first { it.id == "sleep_standby" }.helper)
  }

  @Test
  fun checkedStepsIgnoreTheManualFlag() {
    assertTrue(Progress.isOk(true, false))
    assertFalse(Progress.isOk(false, true))
    assertTrue(Progress.isOk(null, true))
    assertFalse(Progress.isOk(null, false))
  }

  @Test
  fun summaryHasOneLinePerStep() {
    GuidanceFlows.all.forEach { flow ->
      val lines = Progress.summary(flow) { it.kind == StepKind.REQUIRED }
      assertEquals(flow.steps, lines.map { it.step })
      assertEquals(flow.steps.count { it.kind == StepKind.REQUIRED }, lines.count { it.ok })
    }
  }

  @Test
  fun verdictNeedsEveryRequiredStepOk() {
    GuidanceFlows.all.forEach { flow ->
      assertFalse(Progress.isReady(flow) { false })
      assertTrue(Progress.isReady(flow) { true })
      // Recommended steps never block.
      assertTrue(Progress.isReady(flow) { it.kind == StepKind.REQUIRED })
      assertFalse(Progress.isReady(flow) { it.kind == StepKind.RECOMMENDED })
    }
  }

  @Test
  fun doneKeysAreScopedByFlowAndStep() {
    assertEquals("done.oneplus.recents", GuidanceStore.doneKey("oneplus", "recents"))
    assertFalse(GuidanceStore.doneKey("oneplus", "charge") == GuidanceStore.doneKey("samsung", "charge"))
  }

  @Test
  fun fallbackChainEndsAtAppDetailsAndSkipsMissingPermission() {
    assertEquals(
        listOf(GuidanceAction.REQUEST_EXEMPTION, GuidanceAction.BATTERY_SETTINGS, GuidanceAction.APP_DETAILS),
        GuidanceActions.fallbackChain(GuidanceAction.REQUEST_EXEMPTION, canRequestExemption = true),
    )
    assertEquals(
        listOf(GuidanceAction.BATTERY_SETTINGS, GuidanceAction.APP_DETAILS),
        GuidanceActions.fallbackChain(GuidanceAction.REQUEST_EXEMPTION, canRequestExemption = false),
    )
    assertEquals(
        listOf(GuidanceAction.APP_DETAILS),
        GuidanceActions.fallbackChain(GuidanceAction.APP_DETAILS, canRequestExemption = true),
    )
  }
}
