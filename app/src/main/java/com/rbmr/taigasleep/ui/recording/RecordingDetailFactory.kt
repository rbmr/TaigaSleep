package com.rbmr.taigasleep.ui.recording

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.recording.ExportContents
import com.rbmr.taigasleep.recording.ExportProgress
import com.rbmr.taigasleep.recording.ExportResult
import com.rbmr.taigasleep.recording.Exporter
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.RawActionResult
import java.io.File

/**
 * The work behind the recording screen's buttons, behind a port so the view model needs no Android
 * types. Everything here already exists in `recording` and `sync`; this only names the calls the
 * screen makes.
 */
interface RecordingWork {
  suspend fun refreshRecordings()

  fun upload(localId: String)

  /** Whether an account other than the active one already has this recording on its server. */
  suspend fun uploadedElsewhere(localId: String): Boolean

  /** The server and the account an upload goes to now. */
  fun uploadTarget(): UploadTarget?

  suspend fun retryAnalysis(key: String, analysisId: String?): Result<Unit>

  suspend fun restoreRaw(
      localId: String,
      remoteId: String,
      onProgress: (Float) -> Unit,
  ): Result<Unit>

  /** Builds the chosen contents of a night into an archive and offers it to the share sheet. */
  suspend fun share(
      localId: String,
      contents: ExportContents,
      results: List<ExportResult>,
      onProgress: (ExportProgress) -> Unit,
  ): Result<Unit>

  suspend fun deleteRaw(localId: String): RawActionResult

  fun delete(localId: String?, remoteId: String?)

  /** Bytes of the raw streams that are on this phone. */
  fun rawSize(localId: String): Long
}

/**
 * Builds the recording screen's view model from the singletons the app already has. The calls live
 * here, in the one part of the screen that may reach the stores.
 */
class RecordingDetailFactory(
    private val context: Context,
    private val localId: String?,
    private val remoteId: String?,
    private val analysisId: String?,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    val app = context.applicationContext
    val store = app.appContainer.recordingStore
    val sync = app.appContainer.syncService
    val work =
        object : RecordingWork {
          override suspend fun refreshRecordings() {
            RecordingRepository.refreshRecordings(app)
          }

          override fun upload(localId: String) {
            sync.uploadNow(localId)
          }

          override suspend fun uploadedElsewhere(localId: String): Boolean =
              app.appContainer.recordingLibrary.uploadedElsewhere(localId)

          override fun uploadTarget(): UploadTarget? {
            val remote = app.appContainer.remoteRepository
            val server = remote.savedUrl.takeIf { it.isNotBlank() } ?: return null
            return UploadTarget(server = server, account = remote.savedEmail)
          }

          override suspend fun retryAnalysis(key: String, analysisId: String?) =
              sync.retryAnalysis(key, analysisId)

          override suspend fun restoreRaw(
              localId: String,
              remoteId: String,
              onProgress: (Float) -> Unit,
          ) = sync.downloadRaw(localId, onProgress)

          override suspend fun share(
              localId: String,
              contents: ExportContents,
              results: List<ExportResult>,
              onProgress: (ExportProgress) -> Unit,
          ): Result<Unit> =
              runCatching {
                Exporter.share(
                    context = app,
                    recordingId = localId,
                    contents = contents,
                    results = results,
                    onProgress = onProgress,
                )
              }

          override suspend fun deleteRaw(localId: String) = sync.deleteRaw(localId)

          override fun delete(localId: String?, remoteId: String?) {
            sync.delete(localId, remoteId)
          }

          override fun rawSize(localId: String): Long =
              RecordingStore.RAW_FILES.sumOf { File(store.dir(localId), it).length() }
        }
    return RecordingDetailViewModel(
        key = localId ?: remoteId?.let { "remote-$it" } ?: analysisId ?: "recording",
        localId = localId,
        remoteId = remoteId,
        analysisId = analysisId,
        library = app.appContainer.recordingLibrary,
        work = work,
        texts =
            RecordingTexts(
                loadFailed = app.getString(R.string.night_load_failed),
                loginError = app.getString(R.string.upload_error_login),
                rawRestoreFailed = app.getString(R.string.night_raw_restore_failed),
                rawDeleteFailed = app.getString(R.string.night_raw_delete_failed),
                rawNotUploaded = app.getString(R.string.night_raw_not_uploaded),
                errorDetail = app.getString(R.string.night_error_detail),
            ),
    ) as T
  }
}
