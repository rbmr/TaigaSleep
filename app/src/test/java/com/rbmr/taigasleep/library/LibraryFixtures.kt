package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.sync.LibraryEntry
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.recordingIdOf
import com.rbmr.taigasleep.sync.remoteKey

/**
 * The entries the library query publishes for what a test gives it: the server's list, the sync
 * states, the run each recording shows, the payloads on the phone and the recordings the server no
 * longer has. [localIds] are the recordings the phone holds, which is what decides the key of a
 * server recording.
 */
internal fun libraryEntries(
    remote: RecordingSummaryListDto? = null,
    records: Map<String, UploadRecord> = emptyMap(),
    selectedAnalysisIds: Map<String, String> = emptyMap(),
    cachedResults: Map<String, List<AnalysisDto>> = emptyMap(),
    hiddenRemoteIds: Set<String> = emptySet(),
    localIds: Set<String> = emptySet(),
): List<LibraryEntry> {
  val summaries = remote?.items.orEmpty().associateBy { it.recording.id }
  fun keyOf(id: String): String = if (id in localIds) id else remoteKey(id)
  val keys =
      buildSet {
        summaries.keys.forEach { add(keyOf(it)) }
        addAll(records.keys)
        addAll(selectedAnalysisIds.keys)
        addAll(cachedResults.keys)
        hiddenRemoteIds.forEach { add(keyOf(it)) }
      }
  return keys.map { key ->
    val summary = summaries[recordingIdOf(key)]
    LibraryEntry(
        key = key,
        record = records[key],
        recording = summary?.recording,
        runs = summary?.analyses.orEmpty(),
        selectedAnalysisId = selectedAnalysisIds[key],
        cachedResults = cachedResults[key].orEmpty(),
        hidden = recordingIdOf(key) in hiddenRemoteIds,
    )
  }
}
