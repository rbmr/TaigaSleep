package com.rbmr.taigasleep.ui.recording

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.library.RecordingLibrary
import com.rbmr.taigasleep.recording.Exporter
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.RecordingStore
import com.rbmr.taigasleep.sync.RawActionResult
import com.rbmr.taigasleep.sync.RawRetention
import com.rbmr.taigasleep.sync.RecordingDeleter
import com.rbmr.taigasleep.sync.UploadCoordinator
import com.rbmr.taigasleep.sync.UploadScheduler
import java.io.File

/**
 * The work behind the night screen's buttons, behind a port so the view model needs no Android
 * types. Everything here already exists in `recording` and `sync`; this only names the calls the
 * screen makes.
 */
interface NightWork {
  suspend fun refreshRecordings()

  fun upload(localId: String)

  suspend fun retryAnalysis(key: String, analysisId: String?): Result<Unit>

  suspend fun restoreRaw(
      localId: String,
      remoteId: String,
      onProgress: (Float) -> Unit,
  ): Result<Unit>

  suspend fun share(localId: String, onProgress: (Float) -> Unit): Result<Unit>

  suspend fun deleteRaw(localId: String): RawActionResult

  fun delete(localId: String?, remoteId: String?)

  /** Bytes of the raw streams that are on this phone. */
  fun rawSize(localId: String): Long
}

/**
 * Builds the night screen's view model from the singletons the app already has. The calls live
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
    val store = RecordingStore.of(app)
    val work =
        object : NightWork {
          override suspend fun refreshRecordings() {
            RecordingRepository.refreshRecordings(app)
          }

          override fun upload(localId: String) {
            UploadScheduler.enqueueManual(app, localId)
          }

          override suspend fun retryAnalysis(key: String, analysisId: String?) =
              UploadCoordinator.retryAnalysis(app, key, analysisId)

          override suspend fun restoreRaw(
              localId: String,
              remoteId: String,
              onProgress: (Float) -> Unit,
          ) = RawRetention.restore(app, localId, remoteId, onProgress)

          override suspend fun share(localId: String, onProgress: (Float) -> Unit): Result<Unit> =
              runCatching { Exporter.share(app, localId, onProgress) }

          override suspend fun deleteRaw(localId: String) = RawRetention.deleteRaw(app, localId)

          override fun delete(localId: String?, remoteId: String?) {
            RecordingDeleter.delete(app, localId, remoteId)
          }

          override fun rawSize(localId: String): Long =
              RecordingStore.RAW_FILES.sumOf { File(store.dir(localId), it).length() }
        }
    return RecordingDetailViewModel(
        key = localId ?: remoteId?.let { "remote-$it" } ?: analysisId ?: "night",
        localId = localId,
        remoteId = remoteId,
        analysisId = analysisId,
        library = RecordingLibrary.of(app),
        work = work,
        texts =
            NightTexts(
                loadFailed = app.getString(R.string.night_load_failed),
                loginError = app.getString(R.string.upload_error_login),
                rawRestoreFailed = app.getString(R.string.night_raw_restore_failed),
                rawDeleteFailed = app.getString(R.string.night_raw_delete_failed),
                rawNotUploaded = app.getString(R.string.night_raw_not_uploaded),
            ),
    ) as T
  }
}
