package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaUnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

  /**
   * A recording just ended. It always gets a durable sync state; it is queued only when automatic
   * upload is on, the account is logged in and its files are on this phone.
   */
  fun onRecordingFinished(id: String) {
    scope.launch {
      if (environment.record(id) == null) environment.markLocal(id)
      if (!environment.autoUpload.enabled) return@launch
      if (!environment.hasSession) return@launch
      if (!environment.hasRecording(id)) return@launch
      environment.enqueueAuto(id)
    }
  }

  /** The user asked for this recording to go to the server now; the automatic policy does not apply. */
  fun uploadNow(id: String) {
    scope.launch { environment.enqueueManual(id) }
  }

  /**
   * Ask the server for analyses of [id]. An explicit [algorithm] asks for that stager alone; without
   * one the preferred compatible stager is used. The runs are remembered as manual and the recording as
   * processing before the caller reads it back, so the new run is visible as soon as it is queued.
   */
  suspend fun requestAnalysis(
      id: String,
      algorithm: String? = null,
  ): Result<List<StartedAnalysis>> =
      withContext(Dispatchers.IO) {
        val session =
            environment.session()
                ?: return@withContext Result.failure(IllegalStateException("login required"))
        val recordingId = recordingIdOf(id)
        val config = environment.config(id)
        try {
          val started =
              if (algorithm == null) {
                listOf(environment.startPreferred(session.api, recordingId, config))
              } else {
                environment.startManual(session.api, recordingId, config, algorithm)
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
            environment.markProcessing(id, session.account)
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

  /** Restore the remote-delete queue and continue every upload that was interrupted. */
  suspend fun reconcileAll() {
    environment.resumePendingDeletes()
    environment.reconcileUploads()
  }

  /**
   * Read one recording and its analyses from the server and store what it says. [preferred] are
   * analysis ids in order of preference, for example the run the user selected.
   *
   * A 404 means the server does not have the recording, not that it could not be reached: the
   * recording's stored state follows that ([recordingGone]) and the failure says so.
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
          Result.success(
              environment.refresh(
                  session.api,
                  session.account,
                  id,
                  recordingIdOf(id),
                  preferred,
              )
          )
        } catch (e: TaigaUnauthorizedException) {
          if (environment.account == session.account) environment.logout()
          Result.failure(e)
        } catch (e: TaigaHttpException) {
          if (e.status != 404) return@withContext Result.failure(e)
          recordingGone(id, e.message, session.account)
          Result.failure(RecordingNotOnServerException(e.message))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Result.failure(e)
        }
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
    return environment.installedAlgorithms(session.api)
  }

  /** After the automatic-upload settings change, apply them to the queued uploads. */
  fun rescheduleAll() {
    scope.launch { environment.rescheduleAll() }
  }
}
