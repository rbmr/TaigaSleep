package com.rbmr.taigasleep.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.rbmr.taigasleep.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crash and kill diagnostics: append-only JSONL under `getExternalFilesDir(null)/diagnostics/`.
 * See docs/diagnostics.md for the line format and the event vocabulary.
 *
 * Every public method is safe to call from any thread and never throws. Lines logged before [init]
 * are held in memory and written once the store exists. All file I/O happens on one background
 * thread, except crash lines, which are written synchronously on the crashing thread.
 */
object Diagnostics {
  private const val HEARTBEAT_SECONDS = 60L
  private const val SYNC_SECONDS = 30L
  private const val SNAPSHOT_SECONDS = 15L * 60
  private const val TRIM_SECONDS = 60L * 60
  private const val SNAPSHOT_COALESCE_MS = 1000L
  private const val MAX_PENDING = 256

  private val lock = Any()
  private val pending = ArrayList<String>()
  private var app: Application? = null
  private var store: DiagnosticsStore? = null
  private var marker: RunMarker? = null
  private var dedupe: ExitInfoDedupe? = null
  private var executor: ScheduledThreadPoolExecutor? = null

  // Touched only on the diagnostics thread.
  private var lastState: Map<String, Any?>? = null
  private var lastCheckElapsed = 0L
  private var lastHeartbeatElapsed = 0L
  private var snapshotReasons = LinkedHashSet<String>()
  private var snapshotScheduled = false

  /** Appends one line `{"t_ms","elapsed_ms","pid","type",...fields}`. */
  fun log(type: String, fields: Map<String, Any?> = emptyMap()) {
    try {
      val line = JsonLine.build(nowMs(), SystemClock.elapsedRealtime(), Process.myPid(), type, fields)
      synchronized(lock) {
        if (store == null) {
          if (pending.size < MAX_PENDING) pending.add(line)
        } else {
          submit { store?.append(line) }
        }
      }
    } catch (_: Throwable) {}
  }

  /** Like [log], but written and fsynced before returning. For events right before a death. */
  fun logSync(type: String, fields: Map<String, Any?> = emptyMap()) {
    try {
      val line = JsonLine.build(nowMs(), SystemClock.elapsedRealtime(), Process.myPid(), type, fields)
      val s = synchronized(lock) { store }
      if (s == null) {
        synchronized(lock) { if (pending.size < MAX_PENDING) pending.add(line) }
      } else {
        s.append(line)
        s.sync()
      }
    } catch (_: Throwable) {}
  }

  /** Free-form note, for "what was the app doing" context around other lines. */
  fun breadcrumb(msg: String) = log("breadcrumb", mapOf("msg" to msg))

  /**
   * Requests a `settings_snapshot` line. Requests within about a second are merged into one line
   * whose `reason` lists all of them.
   */
  fun snapshot(reason: String) {
    submit {
      snapshotReasons.add(reason)
      if (!snapshotScheduled) {
        snapshotScheduled = true
        executor?.schedule(
            { writeSnapshot() },
            SNAPSHOT_COALESCE_MS,
            TimeUnit.MILLISECONDS,
        )
      }
    }
  }

  /**
   * Re-reads the settings that decide whether we may be stopped and logs a `setting_changed` line
   * (old and new values, and how long since the last look) if any differ. Cheap: call it on
   * every event that can mean the user or the system just changed something.
   */
  fun checkState(source: String) = submit { runCheck(source, null) }

  /** Called by the slow alarm; [scheduledElapsed] is when it was due on the elapsed clock. */
  fun alarmFired(scheduledElapsed: Long) {
    val now = SystemClock.elapsedRealtime()
    val uptime = (now - Process.getStartElapsedRealtime()) / 1000
    log(
        "alarm_heartbeat",
        mapOf(
            "scheduled_elapsed_ms" to scheduledElapsed,
            "late_by_ms" to if (scheduledElapsed > 0) now - scheduledElapsed else null,
            "uptime_s" to uptime,
            // A young process means the alarm itself started us after a kill.
            "process_started_for_alarm" to (uptime < 20),
        ),
    )
    checkState("alarm")
  }

  /** Writes a `diag_heartbeat` now. The scheduled one keeps running regardless. */
  fun heartbeat() = submit { writeHeartbeat() }

  /** Marks the run as alive again (after a clean mark); call when a service starts recording. */
  fun markRunning() {
    submit { marker?.write("running", Process.myPid(), nowMs()) }
  }

  /** Marks the run as finished normally, so the next start does not report an unclean exit. */
  fun markCleanShutdown(reason: String) {
    log("clean_shutdown", mapOf("reason" to reason))
    submit { marker?.write("clean", Process.myPid(), nowMs()) }
  }

  fun onTrimMemory(level: Int) {
    log("trim_memory", mapOf("level" to Names.trimLevel(level), "level_code" to level, "memory" to memoryOrNull()))
  }

  fun onLowMemory() {
    log("low_memory", mapOf("memory" to memoryOrNull()))
  }

  /** Zips the diagnostics directory into the cache dir for sharing. Blocking: call off main. */
  fun exportZip(context: Context): File {
    synchronized(lock) { store }?.sync()
    val dir = diagnosticsDir(context)
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
    context.cacheDir.listFiles { f -> f.name.startsWith("diagnostics_") && f.name.endsWith(".zip") }
        ?.forEach { it.delete() }
    val zip = File(context.cacheDir, "diagnostics_$stamp.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
      for (file in dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()) {
        out.putNextEntry(ZipEntry("diagnostics/${file.name}"))
        file.inputStream().use { it.copyTo(out) }
        out.closeEntry()
      }
    }
    return zip
  }

  fun diagnosticsDir(context: Context): File =
      File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")

  /** Called once from `Application.onCreate`; later calls are ignored. */
  fun init(application: Application) {
    synchronized(lock) {
      if (app != null) return
      app = application
    }
    val dir = diagnosticsDir(application)
    val pid = Process.myPid()
    val runId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date()) + "_$pid"
    val newStore = DiagnosticsStore(dir, runId)
    marker = RunMarker(File(dir, "run_marker"))
    dedupe = ExitInfoDedupe(File(dir, "exit_info_seen"))
    val pool =
        ScheduledThreadPoolExecutor(1) { r ->
          Thread(r, "diagnostics").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
          }
        }
    pool.removeOnCancelPolicy = true
    executor = pool

    // Installed synchronously: a crash during startup must still be recorded.
    CrashHandler.install { thread, error -> recordCrash(newStore, application, thread, error) }

    // The startup task runs first on the queue, so it scans the previous run's files before
    // this run creates its own.
    submit { startup(application, dir, newStore) }
    EventListeners(application).register()
    DiagnosticsAlarm.schedule(application)
    pool.scheduleWithFixedDelay({ writeHeartbeat() }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
    pool.scheduleWithFixedDelay({ newStore.sync() }, SYNC_SECONDS, SYNC_SECONDS, TimeUnit.SECONDS)
    pool.scheduleWithFixedDelay({ snapshot("periodic") }, SNAPSHOT_SECONDS, SNAPSHOT_SECONDS, TimeUnit.SECONDS)
    pool.scheduleWithFixedDelay({ newStore.trim(nowMs()) }, TRIM_SECONDS, TRIM_SECONDS, TimeUnit.SECONDS)
  }

  private fun startup(app: Application, dir: File, newStore: DiagnosticsStore) {
    val now = nowMs()
    val elapsed = SystemClock.elapsedRealtime()
    val previous = PreviousRun.scan(dir)
    val previousMarker = marker?.read()
    marker?.write("running", Process.myPid(), now)
    synchronized(lock) { store = newStore }

    val startElapsed = Process.getStartElapsedRealtime()
    val verdict =
        when {
          previous.file == null && previousMarker == null -> "first_run"
          previousMarker == "clean" -> "clean_shutdown"
          else -> "unclean_exit"
        }
    log(
        "process_start",
        linkedMapOf(
            "process_start_ms" to now - (elapsed - startElapsed),
            "start_elapsed_ms" to startElapsed,
            "process_name" to Application.getProcessName(),
            "debug" to BuildConfig.DEBUG,
            "build_type" to BuildConfig.BUILD_TYPE,
            "git_commit" to BuildConfig.GIT_COMMIT,
            "version_name" to BuildConfig.VERSION_NAME,
            "version_code" to BuildConfig.VERSION_CODE,
            "device" to
                linkedMapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                    "device" to Build.DEVICE,
                    "sdk" to Build.VERSION.SDK_INT,
                    "release" to Build.VERSION.RELEASE,
                    "security_patch" to Build.VERSION.SECURITY_PATCH,
                    "fingerprint" to Build.FINGERPRINT,
                ),
            "previous_run" to
                linkedMapOf(
                    "verdict" to verdict,
                    "marker" to previousMarker,
                    "pid" to previous.pid,
                    "file" to previous.file,
                    "last_event_ms" to previous.lastEventMs,
                    "last_event_type" to previous.lastType,
                    "last_heartbeat_ms" to previous.lastHeartbeatMs,
                    "seconds_since_last_heartbeat" to previous.secondsSinceLastHeartbeat(now),
                    "device_rebooted" to previous.rebooted(elapsed),
                    "last_state" to previous.lastState,
                ),
        ),
    )
    logExitInfo(app)
    // Anything logged before init goes after process_start; the reader sorts by t_ms anyway.
    val early = synchronized(lock) { ArrayList(pending).also { pending.clear() } }
    early.forEach { newStore.append(it) }
    snapshot("process_start")
    writeHeartbeat()
  }

  private fun logExitInfo(app: Application) {
    val seen = dedupe ?: return
    try {
      for (entry in ExitInfoReader.newEntries(app, seen)) log("exit_info", entry)
    } catch (e: Exception) {
      log("exit_info_error", mapOf("error" to e.toString()))
    } finally {
      seen.save()
    }
  }

  private fun writeHeartbeat() {
    val a = app ?: return
    val elapsed = SystemClock.elapsedRealtime()
    val fields = LinkedHashMap(DeviceState.heartbeat(a))
    if (lastHeartbeatElapsed != 0L) fields["since_last_ms"] = elapsed - lastHeartbeatElapsed
    lastHeartbeatElapsed = elapsed
    log("diag_heartbeat", fields)
    // OEM settings screens change the bucket and whitelist without any broadcast, so look here too.
    runCheck("heartbeat", StateDiff.KEYS.associateWith { fields[it] })
  }

  private fun runCheck(source: String, known: Map<String, Any?>?) {
    val a = app ?: return
    val now = SystemClock.elapsedRealtime()
    val state = known ?: DeviceState.volatileState(a)
    val before = lastState
    val late = if (lastCheckElapsed == 0L) null else now - lastCheckElapsed
    lastState = state
    lastCheckElapsed = now
    if (before == null) return
    val changes = StateDiff.diff(before, state)
    if (changes.isEmpty()) return
    log(
        "setting_changed",
        linkedMapOf("source" to source, "observed_late_ms" to late, "changes" to changes),
    )
    if (changes.keys.any { it in StateDiff.CRITICAL }) snapshot("changed:" + changes.keys.joinToString(","))
  }

  private fun writeSnapshot() {
    val a = app ?: return
    val reason = snapshotReasons.joinToString("+")
    snapshotReasons = LinkedHashSet()
    snapshotScheduled = false
    val fields = LinkedHashMap<String, Any?>()
    fields["reason"] = reason
    fields.putAll(DeviceState.snapshot(a))
    log("settings_snapshot", fields)
  }

  private fun recordCrash(s: DiagnosticsStore, a: Application, thread: Thread, error: Throwable) {
    val fields =
        linkedMapOf<String, Any?>(
            "thread" to thread.name,
            "exception" to error.javaClass.name,
            "message" to error.message,
            "stack" to CrashHandler.stackTrace(error),
            "memory" to DeviceState.crashMemory(a),
        )
    val line = JsonLine.build(nowMs(), SystemClock.elapsedRealtime(), Process.myPid(), "crash", fields)
    // Startup normally flushes pending lines, so make sure the store exists even if we died early.
    s.append(line)
    s.sync()
  }

  private fun memoryOrNull(): Any? = app?.let { DeviceState.safe { DeviceState.memory(it) } }

  private fun nowMs() = System.currentTimeMillis()

  private fun submit(task: () -> Unit) {
    try {
      executor?.execute {
        try {
          task()
        } catch (_: Throwable) {}
      }
    } catch (_: RejectedExecutionException) {}
  }
}
