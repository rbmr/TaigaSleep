package com.rbmr.taigasleep.library

import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.RemoteSession
import com.rbmr.taigasleep.backend.recordingSummaries
import com.rbmr.taigasleep.sync.withoutRecordings

/**
 * Reads the server's recording list for the account [session] was taken for. The account is checked
 * again before anything is saved: a login or logout during the fetch means the list belongs to a
 * session that is over, and it is dropped instead of being stored under another account.
 *
 * Returns what was saved, or null when the result was discarded.
 */
internal suspend fun fetchRemoteList(
    session: RemoteSession,
    activeAccount: () -> String?,
    hidden: suspend () -> Set<String>,
    save: suspend (account: String, list: RecordingSummaryListDto) -> Unit,
): RecordingSummaryListDto? {
  val fetched = session.api.recordingSummaries()
  if (activeAccount() != session.account) return null
  // Filtered here, after the fetch: a delete can finish while it is in flight.
  val list = fetched.withoutRecordings(hidden())
  save(session.account, list)
  return list
}
