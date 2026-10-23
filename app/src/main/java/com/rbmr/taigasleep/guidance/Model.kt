package com.rbmr.taigasleep.guidance

import android.content.Context
import androidx.annotation.StringRes

enum class StepKind {
  REQUIRED,
  RECOMMENDED,
}

// Which settings screen a helper button opens. Each kind falls back to the next safest one.
enum class GuidanceAction {
  REQUEST_EXEMPTION,
  BATTERY_SETTINGS,
  APP_DETAILS,
}

// A button that opens the settings screen the step is about.
data class Helper(
    @StringRes val labelRes: Int,
    val action: GuidanceAction,
)

// One setting, shown on one page. A step with a [check] reads its state from the device;
// a step without one is a manual toggle that the user taps and the app remembers.
data class Step(
    val kind: StepKind,
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    // The bulleted steps, shortest useful list.
    val guideRes: List<Int>,
    val helper: Helper? = null,
    val check: ((Context) -> Boolean)? = null,
)

data class Flow(
    val id: String,
    @StringRes val nameRes: Int,
    val steps: List<Step>,
)

enum class Fidelity {
  // Steps were tested on a real device of this brand.
  VERIFIED,
  // Same skin as a tested brand, but not tested on this brand.
  DERIVED,
  // The brand is not known; the generic flow is used.
  UNRECOGNIZED,
}

// What the intro page shows under the text: which flow was picked and how far to trust it.
data class DeviceMatch(
    val flow: Flow,
    val fidelity: Fidelity,
    // For example "Xiaomi 23127PC0AG".
    val deviceLabel: String,
)
