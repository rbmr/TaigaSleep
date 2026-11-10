package com.rbmr.taigasleep.sync

import android.content.Context
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.profile.recordingStartDate
import com.rbmr.taigasleep.recording.store.RecordingMeta
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The date a recording started on, for the age SleepECG uses as a model feature. A recording on the
 * phone carries its own timezone; a recording that is only on the server is dated in the phone's
 * timezone, because the server's recording row does not keep one.
 */
internal suspend fun startDateOf(context: Context, key: String): LocalDate? {
  val container = context.appContainer
  val localId = key.takeUnless { it.startsWith(REMOTE_PREFIX) }
  if (localId != null) {
    val meta = RecordingMeta.read(container.recordingStore.dir(localId))
    if (meta != null) return recordingStartDate(meta.startUtcMs, meta.timezone)
  }
  val account = container.remoteRepository.accountKey.orEmpty()
  val started = container.remoteHistoryCache.startedAt(account, recordingIdOf(key)) ?: return null
  val ms =
      runCatching { OffsetDateTime.parse(started).toInstant().toEpochMilli() }.getOrNull()
          ?: return null
  return recordingStartDate(ms, null)
}

private const val REMOTE_PREFIX = "remote-"
