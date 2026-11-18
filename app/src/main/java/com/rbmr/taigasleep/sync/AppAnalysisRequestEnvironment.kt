package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaException
import com.rbmr.taigasleep.profile.PersonalData
import com.rbmr.taigasleep.recording.store.RecordingMeta
import kotlinx.serialization.json.JsonObject

/**
 * The app's [AnalysisRequestEnvironment]: the recording on the phone and on the server, the profile,
 * the sync state a request leaves and the count of its automatic retries.
 */
internal class AppAnalysisRequestEnvironment(context: Context) : AnalysisRequestEnvironment {
  private val app = context.applicationContext
  private val container get() = app.appContainer
  private val retries = AutomaticRetries(app)

  override fun localMeta(key: String): RecordingMeta? =
      if (isRemoteKey(key)) null else RecordingMeta.read(container.recordingStore.dir(key))

  override suspend fun serverMeta(api: TaigaClient, recordingId: String): JsonObject? =
      try {
        // The meta.json the recording was uploaded with: what it holds, and when it started.
        api.getRecording(recordingId).meta
      } catch (_: TaigaException) {
        // A server that cannot say what the recording holds leaves the request with heart rate only.
        null
      }

  override suspend fun serverStart(recordingId: String): String? =
      container.remoteHistoryCache.startedAt(
          container.remoteRepository.accountKey.orEmpty(),
          recordingId,
      )

  override fun personalData(): PersonalData = container.personalDataStore.data.value

  override suspend fun markProcessing(key: String, account: String) {
    // A login in between means this request belongs to the session that made it.
    if (container.remoteRepository.accountKey == account) {
      container.uploadStore.markProcessing(key, account)
    }
  }

  override fun retriesUsed(recordingId: String, analysis: AnalysisDto): Int =
      retries.used(recordingId, analysis)

  override fun countRetry(recordingId: String, analysis: AnalysisDto) =
      retries.count(recordingId, analysis)
}

/**
 * How often the automatic analysis already asked for one failed run again, kept across starts next
 * to the settings it belongs to.
 */
private class AutomaticRetries(context: Context) {
  private val prefs =
      context.applicationContext.getSharedPreferences("auto_analysis_retries", Context.MODE_PRIVATE)

  fun used(recordingId: String, analysis: AnalysisDto): Int =
      prefs.getInt(key(recordingId, analysis), 0)

  fun count(recordingId: String, analysis: AnalysisDto) {
    val key = key(recordingId, analysis)
    prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
  }

  private fun key(recordingId: String, analysis: AnalysisDto) =
      "$recordingId|${analysis.algorithm}|${analysis.version}"
}
