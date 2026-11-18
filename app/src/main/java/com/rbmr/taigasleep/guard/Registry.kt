package com.rbmr.taigasleep.guard

import android.os.Build
import com.rbmr.taigasleep.debug.DebugTools
import com.rbmr.taigasleep.diagnostics.Maker
import com.rbmr.taigasleep.diagnostics.MakerFamily
import com.rbmr.taigasleep.diagnostics.PhoneState

// Debug-only way to preview another brand's guidance on any phone or emulator.
enum class PreviewDevice(
    val label: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
) {
  ONEPLUS("OnePlus", "OnePlus", "OnePlus", "CPH2769"),
  OPPO("OPPO / Realme", "OPPO", "OPPO", "CPH2557"),
  SAMSUNG("Samsung", "samsung", "samsung", "SM-G991B"),
  GENERIC("Generic (Google)", "Google", "google", "Pixel 8"),
  UNKNOWN("Unknown (Xiaomi)", "Xiaomi", "Xiaomi", "23127PC0AG"),
}

// Maps device brands to a flow. To support a new OS, define its flow and add one entry.
object GuidanceFlows {
  private class Entry(
      val flow: Flow,
      val family: MakerFamily,
      // The lowercase brand names the steps were tested on.
      val verifiedBrands: Set<String>,
  )

  private val entries =
      listOf(
          Entry(OnePlusFlow, MakerFamily.OPLUS, setOf("oneplus")),
          Entry(SamsungFlow, MakerFamily.SAMSUNG, setOf("samsung")),
      )

  val all: List<Flow> = entries.map { it.flow } + GenericFlow

  // No exact brand means the generic flow, even when the family is known by a longer name.
  private fun entryFor(maker: Maker): Entry? =
      if (maker.matched == null) null
      else entries.firstOrNull { it.family == maker.family }

  fun flowFor(manufacturer: String?, brand: String?): Flow =
      entryFor(PhoneState.maker(manufacturer, brand))?.flow ?: GenericFlow

  fun matchFor(manufacturer: String?, brand: String?, model: String?): DeviceMatch {
    val maker = PhoneState.maker(manufacturer, brand)
    val found = entryFor(maker)
    val fidelity =
        when {
          found == null -> Fidelity.UNRECOGNIZED
          maker.matched != null && maker.matched in found.verifiedBrands -> Fidelity.VERIFIED
          else -> Fidelity.DERIVED
        }
    val label =
        listOf(manufacturer?.trim(), model?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
    return DeviceMatch(found?.flow ?: GenericFlow, fidelity, label)
  }

  fun matchFor(device: PreviewDevice): DeviceMatch =
      matchFor(device.manufacturer, device.brand, device.model)

  // The flow the user sees: their own pick if they corrected the detection, else the detected one.
  fun selected(match: DeviceMatch, store: GuidanceStore): Flow =
      all.firstOrNull { it.id == store.flowOverride } ?: match.flow

  // The debug-only preview override is ignored in release builds.
  fun previewOf(store: GuidanceStore): PreviewDevice? =
      if (DebugTools.GUIDANCE_PREVIEW) store.previewDevice else null

  // The flow for the preview device if there is one, else for this phone.
  fun currentMatch(preview: PreviewDevice?): DeviceMatch =
      if (preview != null) matchFor(preview) else matchFor(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
}
