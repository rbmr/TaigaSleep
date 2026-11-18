package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A refresh that answered 404: the server does not have this recording, which is not a network
 * failure.
 */
class RecordingNotOnServerException(message: String? = null) : Exception(message)

/**
 * The one entry point to sync: everything outside this package calls this class and nothing else.
 *
 * Each function does what its call site did before it existed, in the same order. A finished
 * recording gets its durable state and is queued; an explicit request is sent and remembered before
 * the recording is read back; a delete stops the queue before the files go. The rules themselves did not
 * move: the queue, the retention policy and the server calls are the pieces that already existed,
 * and this only names the sequence.
 *
 * The app builds it once per process with [of]; the tests give it a fake [SyncEnvironment].
 *
 * The steps a screen or the recording service starts are handed to [scope] instead of being done on
 * the caller's thread: what they write is durable state, and the caller must not wait for a database
 * write, a queue rebuild or a file to go. The suspend members do their work in the caller's coroutine.
 */
class SyncService internal constructor(
    private val environment: SyncEnvironment,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  /** The durable sync state of every recording, as History and the recording screen observe it. */
  val records: StateFlow<Map<String, UploadRecord>> = environment.records

  /** The queued server deletes of the account that is active, as History observes them. */
  val pendingDeletes: StateFlow<Map<String, PendingDelete>> = environment.pendingDeletes

  /**
   * The rows the library reads: the sync state, the server's list with its runs, the run each
   * recording shows and the payloads this phone holds, for [account] and the recordings on this
   * phone. A write anywhere in the app reaches the screens through the flow.
   */
  fun observeRows(account: String, localIds: List<String>): Flow<List<LibraryEntry>> =
      environment.observeRows(account, localIds)

  /** The same read, once, for a step that is not drawing a screen. */
  suspend fun loadRows(account: String, localIds: List<String>): List<LibraryEntry> =
      environment.loadRows(account, localIds)

  /** The full cached payload of one run, if the active account's copy is on this phone. */
  suspend fun cachedResult(key: String, analysisId: String?): AnalysisDto? =
      environment.cachedResult(key, analysisId)

  /** Whether an account other than the active one already has this recording on its server. */
  suspend fun uploadedElsewhere(localId: String): Boolean =
      environment.uploadedElsewhere(localId)

  /** Remember which finished run the user wants to see. */
  fun selectAnalysis(key: String, analysisId: String?) {
    scope.launch { environment.selectAnalysis(key, analysisId) }
  }

  /** The recordings a list fetched now must leave out, because their server copy is being deleted. */
  suspend fun hiddenRecordings(): Set<String> = environment.hiddenRecordings()

  /** Remember the server's list for the account it was fetched for. */
  suspend fun saveList(account: String, list: RecordingSummaryListDto) =
      environment.saveList(account, list)

  /**
   * Whether the server copy of [recordingId] is on its way out or already gone. Nothing reads such a
   * recording back: it would only answer 404 and undo the delete.
   */
  suspend fun isGone(recordingId: String): Boolean = environment.isGone(recordingId)

  /** The server said the recording is gone: forget its row, without reading it back. */
  suspend fun forget(recordingId: String) = environment.forget(recordingId)

  /**
   * The app started: do everything that is due, and from now on do it again whenever a switch, the
   * server or the account changes, and every few hours. Called once per process.
   */
  fun start() {
    environment.schedulePeriodicCatchUp()
    environment.watchTriggers(scope)
    scope.launch { environment.catchUp(CatchUpReason.APP_START, null) }
  }

  /**
   * Do the automatic upload and analysis work that is due; [only] limits it to one recording.
   * The caller waits for it, which is what a worker needs; the callers that must not wait use
   * [start] or the events below.
   */
  internal suspend fun catchUp(reason: CatchUpReason, only: String? = null) =
      environment.catchUp(reason, only)

  /**
   * A recording just ended. It always gets a durable sync state, and the automatic upload looks
   * at it: whether it goes is that catch-up's decision.
   */
  fun onRecordingFinished(id: String) {
    scope.launch {
      if (environment.record(id) == null) environment.markLocal(id)
      environment.catchUp(CatchUpReason.RECORDING_SAVED, id)
    }
  }

  /**
   * The last of a recording arrived from the tracker. Nothing queued for it before can be trusted:
   * its files grew, so the sync state starts again and the whole recording is uploaded as a new
   * upload, which the server takes the same way as the first one.
   */
  fun onFetchCompleted(id: String) {
    scope.launch {
      environment.cancelUploads(id)
      environment.markLocal(id)
      environment.catchUp(CatchUpReason.RECORDING_SAVED, id)
    }
  }

  /** The user asked for this recording to go to the server now; the automatic policy does not apply. */
  fun uploadNow(id: String) {
    scope.launch { environment.enqueueManual(id) }
  }

  /**
   * Ask the server for analyses of [id]. An explicit [algorithm] asks for that stager alone; without
   * one the preferred compatible stager is used. The requester marks the recording processing and
   * the runs are remembered as manual before the caller reads it back, so the new run is visible as
   * soon as it is queued.
   */
  suspend fun requestAnalysis(
      id: String,
      algorithm: String? = null,
  ): Result<List<StartedAnalysis>> =
      withContext(Dispatchers.IO) {
        val session =
            environment.session()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val started =
              if (algorithm == null) {
                listOf(environment.startPreferred(session, id))
              } else {
                environment.startManual(session, id, algorithm)
              }
          if (started.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("no compatible algorithm is installed")
            )
          }
          // A login in between means these runs belong to the session that asked for them.
          if (environment.account != session.account) {
            return@withContext Result.failure(IllegalStateException("the account changed"))
          }
          for (run in started) {
            environment.markManualAnalysis(id, run.analysisId, session.account)
          }
          Result.success(started)
        } catch (e: TaigaUnauthorizedException) {
          if (environment.account == session.account) environment.logout()
          Result.failure(e)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /**
   * Ask again for a failed analysis, or for one when none exists yet. [analysisId] is the run the
   * screen is showing, so a recording with several analyses retries the one the user is looking at.
   */
  suspend fun retryAnalysis(id: String, analysisId: String? = null): Result<Unit> =
      environment.retry(id, analysisId)

  /**
   * Delete a recording everywhere it exists. The upload queue stops first, so no worker writes the files
   * that are about to go; [id] is the phone's recording id and [remoteId] its server id, which the
   * caller has both of and which are the same for a recording this phone uploaded.
   */
  fun delete(id: String?, remoteId: String? = null) {
    if (id != null) environment.cancelUploads(id)
    scope.launch { environment.deleteRecording(id, remoteId) }
  }

  /**
   * The server no longer has this recording for [account]. A recording on this phone is local again,
   * so it can be uploaded from the recording screen; a recording that was waiting for its analysis
   * without a copy here is marked failed instead of processing for ever.
   */
  suspend fun recordingGone(id: String, detail: String? = null, account: String? = null) {
    val target = account ?: environment.account
    if (environment.hasRecording(id)) {
      environment.markLocal(id, target)
      return
    }
    if (environment.record(id, target)?.phase != UploadPhase.PROCESSING) return
    environment.markAnalysisFailed(id, detail, target)
  }

  /** Restore the remote-delete queue, then do whatever automatic work is due. */
  suspend fun reconcileAll() {
    environment.resumePendingDeletes()
    environment.catchUp(CatchUpReason.APP_RESUME, null)
  }

  /**
   * Read one recording and its analyses from the server and store what it says. [preferred] are
   * analysis ids in order of preference, for example the run the user selected; the run this
   * recording showed last is the last resort. The server's row and the run it shows are remembered
   * here, with the account the read was taken for: a login in between means this recording is not the
   * new account's, so nothing of it is stored for it.
   *
   * A 404 means the server does not have the recording, not that it could not be reached: the
   * recording's stored state follows that ([recordingGone]), its row is forgotten, and the failure
   * says so.
   */
  suspend fun refresh(
      id: String,
      preferred: List<String?> = emptyList(),
  ): Result<RecordingSnapshot> =
      withContext(Dispatchers.IO) {
        val session =
            environment.session()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        try {
          val snapshot =
              environment.refresh(
                  session.api,
                  session.account,
                  id,
                  recordingIdOf(id),
                  preferred + environment.selectedAnalysis(id, session.account),
              )
          if (environment.account == session.account) remember(session.account, id, snapshot)
          Result.success(snapshot)
        } catch (e: TaigaUnauthorizedException) {
          if (environment.account == session.account) environment.logout()
          Result.failure(e)
        } catch (e: TaigaHttpException) {
          if (e.status != 404) return@withContext Result.failure(e)
          recordingGone(id, e.message, session.account)
          if (environment.account == session.account) environment.forget(recordingIdOf(id))
          Result.failure(RecordingNotOnServerException(e.message))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /**
   * The saved list follows a recording that was just read: its row keeps its place, one the list does
   * not have yet is added, and the runs become the ones the server lists now. The run the server says
   * the recording shows is the remembered one until the user picks another; a recording with no run
   * at all forgets it.
   */
  private suspend fun remember(account: String, key: String, snapshot: RecordingSnapshot) {
    environment.saveSummary(account, snapshot)
    environment.selectAnalysis(key, snapshot.shown?.id, account)
  }

  /** Apply the raw-retention policy to every uploaded recording whose cleanup time has passed. */
  suspend fun enforceRawRetention() = environment.enforceRawRetention()

  /** Download this recording's raw files again; the server inflates its compressed copy. */
  suspend fun downloadRaw(id: String, onProgress: (Float) -> Unit = {}): Result<Unit> =
      environment.restoreRaw(id, onProgress)

  /** Remove this recording's raw files from this phone now; the server copy stays. */
  suspend fun deleteRaw(id: String): RawActionResult = environment.deleteRaw(id)

  /** The algorithms the server reports, or the failure to read them. */
  suspend fun installedAlgorithms(): Result<List<AlgorithmDto>> {
    val session =
        environment.session() ?: return Result.failure(IllegalStateException("login required"))
    val installed = environment.installedAlgorithms(session.api)
    val failure = installed.exceptionOrNull()
    if (failure is TaigaUnauthorizedException && environment.account == session.account) {
      environment.logout()
    }
    return installed
  }
}
