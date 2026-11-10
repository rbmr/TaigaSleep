package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import com.rbmr.taigasleep.recording.RecordingRepository
import com.rbmr.taigasleep.recording.store.RecordingMeta
import com.rbmr.taigasleep.recording.store.RecordingPhase
import com.rbmr.taigasleep.recording.store.RecordingStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When the raw stream files of an uploaded recording are removed from this phone.
 *
 * Cleanup never touches the metadata or the events log: those are what History reads after the
 * raw files are gone. Nothing is removed before the server confirmed the upload, and the server
 * copy is always kept.
 */
enum class RawRetentionMode(val storedValue: String, val delayMs: Long?) {
  NEVER("never", null),
  IMMEDIATE("immediate", 0),
  DAYS_5("days_5", TimeUnit.DAYS.toMillis(5)),
  DAYS_14("days_14", TimeUnit.DAYS.toMillis(14)),
  DAYS_30("days_30", TimeUnit.DAYS.toMillis(30));

  companion object {
    fun fromStored(value: String?): RawRetentionMode =
        entries.firstOrNull { it.storedValue == value } ?: NEVER
  }
}

/** True when [nowMs] is at or after the cleanup time of an upload confirmed at [uploadedAtMs]. */
fun isRawCleanupDue(mode: RawRetentionMode, uploadedAtMs: Long, nowMs: Long): Boolean {
  val delay = mode.delayMs ?: return false
  return nowMs >= uploadedAtMs + delay
}

/** The outcome of an explicit raw action on one recording. */
sealed interface RawActionResult {
  data object Done : RawActionResult
  data object NothingToDelete : RawActionResult
  data object Recording : RawActionResult
  data object NotUploaded : RawActionResult
  data class Failed(val detail: String?) : RawActionResult
}

/** Applies the retention policy and performs the per-recording download and delete exceptions. */
internal object RawRetention {
  /**
   * Removes every local raw stream that the policy allows to remove. Safe to call on app start,
   * after an upload and whenever the setting changes: it is a no-op when nothing is due.
   */
  suspend fun enforce(context: Context, nowMs: Long = System.currentTimeMillis()) =
      withContext(Dispatchers.IO) {
        val mode = UploadSettings.get(context).rawRetention.value
        if (mode == RawRetentionMode.NEVER) return@withContext
        val store = context.appContainer.uploadStore
        val recordings = context.appContainer.recordingStore
        var changed = false
        for (recording in recordings.list()) {
          if (recording.state == RecordingPhase.RECORDING) continue
          val record = store.record(recording.id) ?: continue
          if (record.keepRawOnPhone) continue
          val confirmedAt = record.uploadConfirmedAt() ?: continue
          if (!isRawCleanupDue(mode, confirmedAt, nowMs)) continue
          val trimmed =
              runCatching {
                    trim(recording.dir, recordings.lastFrameMs(recording.dir))
                    true
                  }
                  .getOrDefault(false)
          if (trimmed) changed = true
        }
        if (changed) RecordingRepository.refreshRecordings(context)
      }

  /**
   * The explicit exception: remove the raw files of one uploaded recording right now. Metadata,
   * analysis and the server copy stay, so the recording remains in History and the raw files can be
   * downloaded again.
   */
  suspend fun deleteRaw(context: Context, localId: String): RawActionResult =
      withContext(Dispatchers.IO) {
        val recordings = context.appContainer.recordingStore
        val dir = recordings.dir(localId)
        val meta = RecordingMeta.read(dir) ?: return@withContext RawActionResult.NothingToDelete
        if (meta.state == RecordingPhase.RECORDING) return@withContext RawActionResult.Recording
        val record = context.appContainer.uploadStore.record(localId)
        if (record?.uploadConfirmedAt() == null) return@withContext RawActionResult.NotUploaded
        if (!hasStreams(dir)) return@withContext RawActionResult.NothingToDelete
        try {
          trim(dir, recordings.lastFrameMs(dir))
          RecordingRepository.refreshRecordings(context)
          RawActionResult.Done
        } catch (e: IOException) {
          RawActionResult.Failed(e.message)
        }
      }

  /**
   * The other explicit exception: download the raw files of one uploaded recording again. The
   * server inflates its compressed copy, so the files written here are the original streams.
   * A restored recording is protected from automatic cleanup until the user deletes it explicitly.
   */
  suspend fun restore(
      context: Context,
      localId: String,
      recordingId: String,
      onProgress: (Float) -> Unit = {},
  ): Result<Unit> =
      withContext(Dispatchers.IO) {
        val recordings = context.appContainer.recordingStore
        val dir = recordings.dir(localId)
        val meta = RecordingMeta.read(dir)
        if (meta == null) {
          return@withContext Result.failure(
              IllegalStateException("the recording metadata is missing")
          )
        }
        if (meta.state == RecordingPhase.RECORDING) {
          return@withContext Result.failure(
              IllegalStateException("the recording is still in progress")
          )
        }
        val remote = context.appContainer.remoteRepository
        val api =
            remote.api()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        val restored = mutableListOf<File>()
        try {
          val available =
              api.listFiles(recordingId).items.filter {
                it.name in RecordingStore.RAW_FILES && it.size > 0
              }
          if (available.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("the server has no raw files for this recording")
            )
          }
          available.map { it.name }.forEachIndexed { index, name ->
            val target = File(dir, name)
            val part = File(dir, "$name.part")
            part.delete()
            try {
              api.downloadFile(recordingId, name, part)
              Files.move(
                  part.toPath(),
                  target.toPath(),
                  StandardCopyOption.REPLACE_EXISTING,
              )
              restored += target
            } finally {
              part.delete()
            }
            onProgress((index + 1).toFloat() / available.size)
          }
          context.appContainer.uploadStore.markKeepRaw(localId)
          RecordingRepository.refreshRecordings(context)
          Result.success(Unit)
        } catch (e: CancellationException) {
          restored.forEach { it.delete() }
          throw e
        } catch (e: TaigaUnauthorizedException) {
          restored.forEach { it.delete() }
          remote.logout()
          Result.failure(e)
        } catch (e: Exception) {
          restored.forEach { it.delete() }
          Result.failure(e)
        }
      }

  /** When the server last confirmed the upload, or null while no server copy exists. */
  internal fun UploadRecord.uploadConfirmedAt(): Long? {
    uploadedAtMs?.let { return it }
    // Records written before uploadedAtMs existed fall back to their last transition.
    return when (phase) {
      UploadPhase.PROCESSING,
      UploadPhase.DONE -> updatedAtMs.takeIf { it > 0 }
      else -> null
    }
  }

  private fun hasStreams(dir: File): Boolean =
      RecordingStore.RAW_FILES.any { name -> File(dir, name).let { it.isFile && it.length() > 0 } }

  /**
   * Deletes the raw streams after making sure the end time is in meta.json, because the last HR
   * frame is where an unfinished recording used to get its end time from.
   */
  private fun trim(dir: File, lastFrameMs: Long?) {
    val meta = RecordingMeta.read(dir)
    if (meta != null && meta.endUtcMs == null) {
      if (!meta.copy(endUtcMs = lastFrameMs ?: meta.startUtcMs).write(dir)) {
        // Keep the streams until the end time is saved; they are where it comes from.
        throw IOException("could not update ${RecordingMeta.FILE_NAME}")
      }
    }
    for (name in RecordingStore.RAW_FILES) {
      val file = File(dir, name)
      if (!file.isFile) continue
      if (!file.delete()) throw IOException("could not delete ${file.name}")
    }
  }
}
