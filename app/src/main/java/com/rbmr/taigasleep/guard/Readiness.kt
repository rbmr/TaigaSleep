package com.rbmr.taigasleep.guard

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import com.rbmr.taigasleep.tracker.RecordingMode

/**
 * The phone settings that decide whether a recording survives. Read once per answer, never decided
 * here: the flow's required steps own the decision, so this only reports what the system says.
 */
data class Readiness(
    val ignoringBatteryOptimizations: Boolean,
    // Null when the system did not answer.
    val standbyBucket: Int?,
    val backgroundRestricted: Boolean,
) {
  /** What the standard exemption popup grants, and all the generic flow requires. */
  val batteryExempted: Boolean
    get() = ignoringBatteryOptimizations

  /**
   * What OnePlus calls "Allow background activity" and Samsung "Unrestricted": whitelisted, in the
   * exempted standby bucket and not background restricted.
   */
  val backgroundAllowed: Boolean
    get() =
        ignoringBatteryOptimizations &&
            standbyBucket == BUCKET_EXEMPTED &&
            !backgroundRestricted

  fun summary() =
      "ignoring_battery_optimizations=$ignoringBatteryOptimizations " +
          "standby_bucket=$standbyBucket background_restricted=$backgroundRestricted"

  companion object {
    /** `STANDBY_BUCKET_EXEMPTED`, a hidden constant in the SDK. */
    const val BUCKET_EXEMPTED = 5

    fun read(context: Context): Readiness {
      val app = context.applicationContext
      val bucket =
          runCatching { app.getSystemService(UsageStatsManager::class.java).appStandbyBucket }
              .getOrNull()
      return Readiness(
          ignoringBatteryOptimizations =
              app.getSystemService(PowerManager::class.java)
                  .isIgnoringBatteryOptimizations(app.packageName),
          standbyBucket = bucket,
          backgroundRestricted =
              app.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
      )
    }
  }
}

/**
 * The two states of Home's preventing-shutdown card. On-device recording (#19) adds a third, and
 * until then every recording needs the phone awake and connected.
 */
enum class HomeState {
  READY,
  NEEDS_SETTINGS,
  /** The chosen mode stores the recording on the tracker, so the phone may sleep. */
  NOT_NEEDED,
}

/**
 * The one answer Home, the flow and the recording service share: do the required steps of the flow
 * the app will show pass on this phone? The flow owns the criterion per OS, so the card, the step
 * pages and the service start gate cannot disagree, and the user's own flow pick is honoured
 * everywhere.
 *
 * The card is shown only while preventing shutdown matters. A streamed recording needs it; a
 * recording stored on the tracker does not, because the phone may sleep. See "Preventing shutdown"
 * in `docs/vision.md`.
 */
object ReadinessState {
  /** The flow the app will show: the user's own pick if they corrected the detection. */
  fun flow(store: GuidanceStore, preview: PreviewDevice? = null): Flow =
      GuidanceFlows.selected(GuidanceFlows.currentMatch(preview), store)

  fun state(flow: Flow, isOk: (Step) -> Boolean): HomeState =
      if (Progress.isReady(flow, isOk)) HomeState.READY else HomeState.NEEDS_SETTINGS

  /** The mode decides whether the flow applies at all; no other input matters on the tracker. */
  fun state(flow: Flow, mode: RecordingMode, isOk: (Step) -> Boolean): HomeState =
      if (mode == RecordingMode.ON_DEVICE) HomeState.NOT_NEEDED else state(flow, isOk)

  fun state(readiness: Readiness, store: GuidanceStore, flow: Flow): HomeState =
      state(flow) { Progress.isOk(readiness, store, flow, it) }

  /**
   * The state Home shows. Pass [GuidanceFlows.previewOf] for the debug preview and null in the
   * service, which must never follow a preview.
   */
  fun state(context: Context, store: GuidanceStore, preview: PreviewDevice?): HomeState =
      state(Readiness.read(context), store, flow(store, preview))

  /** The mode decides whether the check applies at all. */
  fun state(
      context: Context,
      store: GuidanceStore,
      preview: PreviewDevice?,
      mode: RecordingMode,
  ): HomeState =
      if (mode == RecordingMode.ON_DEVICE) HomeState.NOT_NEEDED
      else state(context, store, preview)

  /** The shared result, for the recording service's start gate. */
  fun ready(
      context: Context,
      store: GuidanceStore,
      preview: PreviewDevice?,
      mode: RecordingMode,
  ): Boolean = state(context, store, preview, mode) != HomeState.NEEDS_SETTINGS
}
