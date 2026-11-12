package com.rbmr.taigasleep.backend

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisDtoTest {
  private val json =
      Json {
        ignoreUnknownKeys = true
        explicitNulls = false
      }

  @Test
  fun aSucceededAnalysisCarriesItsSummaryAndItsResult() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """
            {
              "id": "a1b2c3d4e5f6g7h",
              "recording": "20261023_223000-k3f9",
              "algorithm": "wav2sleep",
              "version": "0.1.0",
              "params": {},
              "status": "succeeded",
              "error": "",
              "attempts": 1,
              "created": "2026-10-24T05:52:40Z",
              "updated": "2026-10-24T05:58:03Z",
              "finished_at": "2026-10-24T05:58:03Z",
              "summary": {
                "stages": {"epoch_seconds": 30, "start_unix_ms": 1000, "count": 3,
                           "segments": [{"stage": "wake", "count": 1}, {"stage": "light", "count": 2}]},
                "scores": [{"id": "sleep_quality", "value": 79.0}]
              },
              "result": {
                "contract_version": 3,
                "model": "wav2sleep",
                "model_version": "0.1.0",
                "params": {},
                "params_hash": "abc",
                "stages": {"epoch_seconds": 30, "start_unix_ms": 1000, "count": 3,
                           "segments": [{"stage": "wake", "count": 1}, {"stage": "light", "count": 2}],
                           "confidence": [0.9, 0.8, 0.7], "artifacts": [false, false, false]},
                "items": [
                  {"kind": "number", "id": "total_sleep_s", "label": "Total asleep",
                   "group": "sleep", "unit": "s", "value": 60.0,
                   "explanation": "Time scored as sleep.", "sources": ["stages"]},
                  {"kind": "score", "id": "sleep_quality", "label": "Sleep score",
                   "group": "score", "unit": "%", "value": 79.0,
                   "explanation": "Weighted sleep figures.", "sources": ["stages"],
                   "components": [{"id": "sleep_efficiency", "label": "Sleep efficiency",
                                   "weight": 100.0, "value": 79.0}]},
                  {"kind": "series", "id": "hr", "label": "Heart rate", "group": "heart",
                   "unit": "bpm", "explanation": "Mean heart rate per epoch.", "sources": ["hr"],
                   "series": {"epoch_seconds": 30, "count": 1, "mean_bpm": [58.0]}}
                ],
                "capabilities": {"stages": true},
                "provenance": {"worker": "test"},
                "warnings": ["short night"]
              }
            }
            """
        )

    assertEquals("20261023_223000-k3f9", analysis.recording)
    assertEquals("wav2sleep", analysis.algorithm)
    assertEquals("0.1.0", analysis.version)
    assertTrue(analysis.isSucceeded)
    assertFalse(analysis.isFailed)
    assertEquals(1, analysis.attempts)
    assertEquals("2026-10-24T05:58:03Z", analysis.finished_at)
    assertEquals(79.0, analysis.summary?.scores?.single()?.value ?: 0.0, 0.001)
    assertEquals(3, analysis.summary?.stages?.count)

    val result = analysis.result
    assertEquals(3, result?.contract_version)
    assertEquals(3, result?.stages?.count)
    assertEquals("light", result?.stages?.segments?.get(1)?.stage)
    assertEquals(listOf("short night"), result?.warnings)
    assertEquals(3, result?.items?.size)
    val number = result?.items?.first { it.kind == "number" }
    assertEquals("Total asleep", number?.label)
    assertEquals(60.0, number?.value ?: 0.0, 0.001)
    assertEquals(listOf("stages"), number?.sources)
    val score = result?.items?.first { it.kind == "score" }
    assertEquals("Sleep efficiency", score?.components?.single()?.label)
    assertEquals(79.0, score?.value ?: 0.0, 0.001)
    assertEquals(58.0, result?.items?.first { it.kind == "series" }?.series?.mean_bpm?.single() ?: 0.0, 0.001)
  }

  @Test
  fun aListedAnalysisHasASummaryAndNoResult() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """
            {
              "id": "a1", "recording": "r1", "algorithm": "baseline", "version": "1.0.0",
              "params": {}, "status": "succeeded", "error": "", "attempts": 1,
              "created": "2026-10-24T05:52:40Z", "updated": "2026-10-24T05:58:03Z",
              "finished_at": "2026-10-24T05:58:03Z",
              "summary": {"scores": [{"id": "data_quality", "value": 80.0}],
                          "stages": {"epoch_seconds": 30, "count": 1,
                                     "segments": [{"stage": "light", "count": 1}]}}
            }
            """
        )

    assertNull(analysis.result)
    assertEquals(80.0, analysis.summary?.scores?.single()?.value ?: 0.0, 0.001)
    assertEquals("light", analysis.summary?.stages?.segments?.get(0)?.stage)
  }

  @Test
  fun aQueuedAnalysisHasNeitherSummaryNorResult() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """
            {"id": "a1", "recording": "r1", "algorithm": "baseline", "version": "1.0.0",
             "params": {}, "status": "queued", "error": "", "attempts": 0,
             "created": "2026-10-24T05:52:40Z", "updated": "2026-10-24T05:52:40Z",
             "finished_at": null, "summary": null}
            """
        )

    assertFalse(analysis.isSucceeded)
    assertNull(analysis.finished_at)
    assertNull(analysis.summary)
    assertNull(analysis.result)
  }

  @Test
  fun aFailedAnalysisKeepsItsError() {
    val analysis =
        json.decodeFromString<AnalysisDto>(
            """{"id": "a1", "status": "failed", "error": "worker ran out of memory"}"""
        )

    assertTrue(analysis.isFailed)
    assertEquals("worker ran out of memory", analysis.error)
  }

  @Test
  fun theHeartRateSeriesKeepsItsGaps() {
    val result =
        json.decodeFromString<AnalysisResultDto>(
            """
            {
              "contract_version": 3,
              "items": [
                {"kind": "series", "id": "hr", "label": "Heart rate", "group": "heart",
                 "unit": "bpm", "explanation": "Mean heart rate per epoch.", "sources": ["hr"],
                 "series": {
                   "epoch_seconds": 30,
                   "start_unix_ms": 1790000000000,
                   "count": 3,
                   "mean_bpm": [60.0, null, 58.0],
                   "sample_count": [30, 0, 30]
                 }}
              ]
            }
            """
        )

    val series = result.items.single().series
    assertEquals(listOf(60.0, null, 58.0), series?.mean_bpm)
    assertEquals(3, series?.count)
  }

  @Test
  fun aRecordingParsesItsOpenEndAndItsSeal() {
    val recording =
        json.decodeFromString<RecordingDto>(
            """
            {"id": "20261023_223000-k3f9", "state": "recording", "device": "Polar H10 069A4737",
             "started_at": "2026-10-23T20:30:00Z", "ended_at": null, "duration_s": 0,
             "complete": false, "stored_bytes": 12,
             "meta": {"format": "taiga-recording", "version": 1},
             "created": "2026-10-23T20:30:01Z", "updated": "2026-10-23T20:30:01Z"}
            """
        )

    assertEquals("20261023_223000-k3f9", recording.id)
    assertNull(recording.ended_at)
    assertFalse(recording.complete)
    assertEquals("Polar H10 069A4737", recording.device)
    assertEquals(12L, recording.stored_bytes)
  }

  @Test
  fun anAlgorithmListKeepsTheManifestAndIgnoresWhatItDoesNotKnow() {
    val list =
        json.decodeFromString<AlgorithmListDto>(
            """
            {"items": [
              {"id": "baseline", "version": "1.0.0", "name": "Baseline", "license": "MIT",
               "needs": [{"name": "hr", "required": true}], "epoch_seconds": 30,
               "supported_stages": ["wake", "light"], "source": "built-in",
               "workers": ["w1"]}
            ]}
            """
        )

    assertEquals("baseline", list.items.single().id)
    assertEquals("hr", list.items.single().needs.single().name)
  }
}
