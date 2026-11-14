package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.R

/**
 * The plain sentence for a failed analysis. The server reports the worker's reason as
 * `code: detail`; only the code is used, and anything unknown falls back to a general sentence.
 */
internal fun analysisFailureText(detail: String?): Int =
    when (detail?.substringBefore(':')?.trim()) {
      "recording_too_short" -> R.string.night_analysis_failed_too_short
      "insufficient_data",
      "insufficient_beats" -> R.string.night_analysis_failed_not_enough_data
      "missing_stream" -> R.string.night_analysis_failed_missing_data
      "bad_ecg" -> R.string.night_analysis_failed_bad_ecg
      else -> R.string.night_analysis_failed_body
    }
