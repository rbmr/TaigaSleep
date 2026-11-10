package com.rbmr.taigasleep.insights

/**
 * The names of the algorithms the worker ships, keyed by the id an analysis carries. The server's
 * catalogue has the name too, but a finished analysis only stores the id, so the screens that show
 * one read the name here.
 */
object AlgorithmNames {
  private val names =
      mapOf(
          "baseline" to "Baseline HR/ACC",
          "wav2sleep" to "wav2sleep ECG",
          "sleep-ecg" to "SleepECG",
      )

  /** The name to show for an algorithm id; an unknown algorithm keeps its id. */
  fun display(id: String): String = names[id] ?: id
}
