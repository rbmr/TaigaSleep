package com.rbmr.taigasleep.guidance

import android.os.Build
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.GuidanceStore

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
      // Lowercase manufacturer or brand names that use this flow.
      val brands: Set<String>,
      // The subset of [brands] the steps were tested on.
      val verifiedBrands: Set<String>,
  )

  private val entries =
      listOf(
          Entry(OnePlusFlow, setOf("oneplus", "oppo", "realme"), setOf("oneplus")),
          Entry(SamsungFlow, setOf("samsung"), setOf("samsung")),
      )

  val all: List<Flow> = entries.map { it.flow } + GenericFlow

  private fun normalize(value: String?): String = value?.trim()?.lowercase().orEmpty()

  // The manufacturer wins over the brand; unknown or blank values fall back to generic.
  private fun entryFor(manufacturer: String?, brand: String?): Pair<Entry, String>? =
      listOf(manufacturer, brand).map(::normalize).firstNotNullOfOrNull { name ->
        entries.firstOrNull { name in it.brands }?.let { it to name }
      }

  fun flowFor(manufacturer: String?, brand: String?): Flow =
      entryFor(manufacturer, brand)?.first?.flow ?: GenericFlow

  fun matchFor(manufacturer: String?, brand: String?, model: String?): DeviceMatch {
    val found = entryFor(manufacturer, brand)
    val fidelity =
        when {
          found == null -> Fidelity.UNRECOGNIZED
          found.second in found.first.verifiedBrands -> Fidelity.VERIFIED
          else -> Fidelity.DERIVED
        }
    val label =
        listOf(manufacturer?.trim(), model?.trim()).filter { !it.isNullOrEmpty() }.joinToString(" ")
    return DeviceMatch(found?.first?.flow ?: GenericFlow, fidelity, label)
  }

  fun matchFor(device: PreviewDevice): DeviceMatch =
      matchFor(device.manufacturer, device.brand, device.model)

  // The flow the user sees: their own pick if they corrected the detection, else the detected one.
  fun selected(match: DeviceMatch, store: GuidanceStore): Flow =
      all.firstOrNull { it.id == store.flowOverride } ?: match.flow

  // The debug-only preview override is ignored in release builds.
  fun previewOf(store: GuidanceStore): PreviewDevice? =
      if (BuildConfig.DEBUG) store.previewDevice else null

  // The flow for the preview device if there is one, else for this phone.
  fun currentMatch(preview: PreviewDevice?): DeviceMatch =
      if (preview != null) matchFor(preview) else matchFor(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
}
