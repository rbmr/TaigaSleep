package com.rbmr.taigasleep.ui.recording

import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisFailureTest {
  @Test
  fun `a known reason gets its own sentence`() {
    assertEquals(
        R.string.night_analysis_failed_too_short,
        analysisFailureText("recording_too_short: recording is 22.531s. baseline needs at least 30s"),
    )
    assertEquals(
        R.string.night_analysis_failed_missing_data,
        analysisFailureText("missing_stream: algorithm 'wav2sleep' requires a ECG stream"),
    )
    assertEquals(R.string.night_analysis_failed_not_enough_data, analysisFailureText("insufficient_beats"))
    assertEquals(R.string.night_analysis_failed_bad_ecg, analysisFailureText("bad_ecg: flat signal"))
  }

  @Test
  fun `an unknown or missing reason gets the general sentence`() {
    assertEquals(R.string.night_analysis_failed_body, analysisFailureText("invalid_result: x"))
    assertEquals(R.string.night_analysis_failed_body, analysisFailureText(null))
    assertEquals(R.string.night_analysis_failed_body, analysisFailureText(""))
  }
}
