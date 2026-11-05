package com.rbmr.taigasleep.remote

import java.io.File
import kotlinx.serialization.json.JsonObject

/** A [TaigaClient] whose calls all fail, so a fake only overrides what the test exercises. */
open class StubTaigaClient : TaigaClient {
  override suspend fun putRecording(id: String, metaJson: String): RecordingDto = unexpected()

  override suspend fun getRecording(id: String): RecordingDto = unexpected()

  override suspend fun headFile(id: String, file: String): RemoteFile? = unexpected()

  override suspend fun appendFile(
      id: String,
      file: String,
      offset: Long,
      bytes: ByteArray,
      encoding: String?,
  ): Long = unexpected()

  override suspend fun completeRecording(id: String): RecordingDto = unexpected()

  override suspend fun requestAnalysis(
      recordingId: String,
      algorithm: String,
      version: String,
      params: JsonObject?,
  ): AnalysisDto = unexpected()

  override suspend fun getAlgorithms(): AlgorithmListDto = unexpected()

  override suspend fun deleteRecording(id: String): Unit = unexpected()

  override suspend fun getAnalysis(analysisId: String): AnalysisDto = unexpected()

  override suspend fun listFiles(id: String): RecordingFileListDto = unexpected()

  override suspend fun downloadFile(id: String, file: String, target: File): Unit = unexpected()

  override suspend fun listRecordings(limit: Int): RecordingListDto = unexpected()

  override suspend fun listAnalyses(recording: String?, limit: Int, offset: Int): AnalysisListDto =
      unexpected()

  private fun unexpected(): Nothing = throw UnsupportedOperationException()
}
