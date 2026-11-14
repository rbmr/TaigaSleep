package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.backend.allAnalyses
import com.rbmr.taigasleep.insights.AnalysisSettings
import com.rbmr.taigasleep.recording.RecordingPrefs
import com.rbmr.taigasleep.recording.RecordingService
import com.rbmr.taigasleep.recording.store.RecordingConfig
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import kotlinx.coroutines.CancellationException

/** The app's [AutomationEnvironment]: every member is a call the app already had. */
internal class AppAutomationEnvironment(context: Context) : AutomationEnvironment {
  private val app = context.applicationContext
  private val container get() = app.appContainer
  private val retries = app.getSharedPreferences("auto_analysis_retries", Context.MODE_PRIVATE)

  override val account: String?
    get() = container.remoteRepository.accountKey

  override val autoUpload: AutoUploadSettings
    get() = UploadSettings.get(app).autoUpload.value

  override val analysis: AnalysisSettings
    get() = container.analysisSettingsStore.settings.value

  override fun nowMs(): Long = System.currentTimeMillis()

  override suspend fun uploadNights(only: String?): List<UploadNight> {
    val account = account ?: return emptyList()
    val store = container.uploadStore
    val running = RecordingService.running
    val active = RecordingPrefs.get(app).activeSessionId
    return container.recordingStore
        .list()
        .filter { only == null || it.id == only }
        .map { recording ->
          var record = store.record(recording.id, account)
          // An upload the phone left at its end without the server's word is asked about first,
          // so a confirmation cannot stay missing.
          if (record != null && record.awaitsUploadConfirmation()) {
            val api = container.remoteRepository.session()?.api
            if (api != null && confirmUploadOnServer(api, recording.id, store, account)) {
              record = store.record(recording.id, account)
            }
          }
          UploadNight(
              id = recording.id,
              state = recording.state,
              live = recording.state == RecordingPhase.RECORDING && (running || recording.id == active),
              record = record,
          )
        }
  }

  override suspend fun queueUpload(id: String, replace: Boolean) =
      UploadScheduler.queueAuto(app, id, replace)

  override suspend fun stopAutoUploads() = UploadScheduler.stopAuto(app)

  override suspend fun analysisNights(only: String?): List<AnalysisNight> {
    val account = account ?: return emptyList()
    val local = container.recordingStore.list().map { it.id }
    return container.recordingRows
        .load(account, local)
        .filter { !it.hidden && (only == null || it.key == only) }
        .map { entry ->
          AnalysisNight(
              key = entry.key,
              recordingId = recordingIdOf(entry.key),
              record = entry.record,
              config = configOf(entry),
              complete = entry.recording?.complete == true,
              cachedRuns = entry.runs,
          )
        }
  }

  /** The streams of a night: its own meta.json, or the one the server keeps for a remote night. */
  private fun configOf(entry: LibraryEntry): RecordingConfig? {
    if (!entry.key.startsWith(REMOTE_KEY_PREFIX)) {
      RecordingMeta.read(container.recordingStore.dir(entry.key))?.let {
        return it.config
      }
    }
    val meta = entry.recording?.meta ?: return null
    return try {
      RecordingMeta.fromJson(meta).config
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  override fun session(): RemoteSession? = container.remoteRepository.session()

  override suspend fun installed(session: RemoteSession): List<AlgorithmDto>? =
      AnalysisService.installedAlgorithms(session.api).getOrNull()

  override suspend fun runs(session: RemoteSession, recordingId: String): List<AnalysisDto>? =
      try {
        session.api.allAnalyses(recordingId)
      } catch (e: TaigaUnauthorizedException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }

  override suspend fun requestAnalysis(
      session: RemoteSession,
      night: AnalysisNight,
      choice: StagerChoice,
      retry: AnalysisDto?,
  ) {
    val params =
        retry?.params
            ?: analysisParams(
                choice.id,
                container.personalDataStore.data.value,
                startDateOf(app, night.key),
            )
    session.api.requestAnalysis(night.recordingId, choice.id, choice.version, params)
    if (retry != null) {
      val key = retryKey(night.recordingId, retry)
      retries.edit().putInt(key, retries.getInt(key, 0) + 1).apply()
    }
    // A login in between means this request belongs to the session that made it.
    if (account == session.account) container.uploadStore.markProcessing(night.key, session.account)
  }

  override fun sessionRejected(session: RemoteSession) {
    if (account == session.account) container.remoteRepository.logout()
  }

  override fun retriesUsed(recordingId: String, analysis: AnalysisDto): Int =
      retries.getInt(retryKey(recordingId, analysis), 0)

  private fun retryKey(recordingId: String, analysis: AnalysisDto) =
      "$recordingId|${analysis.algorithm}|${analysis.version}"
}
