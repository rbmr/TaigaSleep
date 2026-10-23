# Diagnostics

If the app crashes or the phone kills it, the files described here should be enough to say why,
without a debugging session. Package: `com.rbmr.taigasleep.diagnostics`.

## Where and how

Directory: `getExternalFilesDir(null)/diagnostics/`, i.e.
`/sdcard/Android/data/com.rbmr.taigasleep/files/diagnostics/` (falls back to the app's internal
`files/diagnostics/` if external storage is unavailable).

- **One file per process run**: `diag_<yyyyMMdd_HHmmss UTC>_<pid>.jsonl`. A run that exceeds 4 MB
  continues in `diag_<...>_002.jsonl` and so on. File names sort chronologically.
- JSON Lines, UTF-8, one event per line. Every line starts with the same four keys:

  ```json
  {"t_ms":1792800000000,"elapsed_ms":123456,"pid":4242,"type":"diag_heartbeat", ...fields}
  ```

  `t_ms` is wall-clock epoch milliseconds (can jump if the clock is changed, see `time_changed`),
  `elapsed_ms` is `SystemClock.elapsedRealtime()` (monotonic, counts deep sleep, resets on reboot).
  Fields named like a header key are renamed `f_<name>`. Strings are cut at 8192 characters.
- Each line is one unbuffered write, so a kill loses at most the line in flight. The file is
  fsynced every 30 s and immediately after crash lines and `logSync`.
- Writes happen on one daemon thread (`diagnostics`). Callers never block on disk.
- **Size cap**: at most 20 MB in total and 14 days of files. Trimming (oldest files first, never
  the file being written) runs at startup, every hour and whenever a file rolls over, on the
  diagnostics thread.
- Other files in the directory: `run_marker` (`state=running|clean`), `exit_info_seen` (dedupe
  keys for exit-info entries). They are not trimmed and are tiny.
- Cost: one line per minute (about 450 bytes), a settings snapshot (about 2 KB) every 15 minutes
  and on changes, plus events. Roughly 1 MB per day.

## Privacy

Recorded: device identity, app version, OS settings that affect background survival, battery of
the phone, memory, stack traces, kill reasons. Never recorded: heart-rate or any other health
samples, sensor data, accounts, location, or other apps' package names. The sensor's id may be
logged by callers (allowed). `exit_info` includes the system's description string and, for
crashes/ANRs, the first 4 KB of the trace (own process only).

## API

All methods are on the `Diagnostics` object, are safe from any thread, safe before `init`
(lines are held in memory, up to 256), and never throw.

```kotlin
Diagnostics.log("service_start", mapOf("reason" to "user", "null_intent" to false))
Diagnostics.breadcrumb("tapped start recording")       // type "breadcrumb", {"msg": ...}
Diagnostics.snapshot("service_start")                  // settings_snapshot, merged within ~1 s
Diagnostics.heartbeat()                                // extra diag_heartbeat now
Diagnostics.logSync("watchdog_giving_up", mapOf(...))  // written + fsynced before returning
Diagnostics.markRunning()                              // see "Run marker"
Diagnostics.markCleanShutdown("user_stopped")
val zip: File = Diagnostics.exportZip(context)         // blocking, call off the main thread
```

`log` values may be `null`, `Boolean`, numbers, strings, enums, maps and lists (nested up to 6
levels); anything else is stored with `toString()`. Never put sample values in the fields.

`TaigaApplication.onCreate` calls `Diagnostics.init`. Nothing else has to.

## What is recorded automatically

`init` installs the crash handler, registers the listeners, starts the timers and queues the
startup work (scan the previous run, log `process_start`, log new exit-info entries, first
snapshot, first heartbeat).

### Event vocabulary (`type`)

| type | when | main fields |
|---|---|---|
| `process_start` | every process start | `process_start_ms`, `start_elapsed_ms`, `process_name`, `debug`, `version_name`, `version_code`, `device{manufacturer,brand,model,device,sdk,release,security_patch,fingerprint}`, `previous_run{verdict,marker,pid,file,last_event_ms,last_event_type,last_heartbeat_ms,seconds_since_last_heartbeat,device_rebooted,last_state{uptime_s,bucket,whitelisted,background_restricted,power_save,doze,interactive,locked,plugged,charging,battery_pct,importance}}` |
| `exit_info` | once per `ApplicationExitInfo` entry not logged before (dedupe by timestamp+pid, persisted) | `event_ms` (when the process died), `exit_pid`, `reason`, `reason_code`, `status`, `importance`, `description` (e.g. `o-kill(6)`), `pss_kb`, `rss_kb`, `trace` (ANR/crash only, first 4 KB) |
| `crash` | uncaught exception (then chains to the previous handler) | `thread`, `exception`, `message`, `stack` (with causes), `memory` |
| `diag_heartbeat` | every 60 s, and `Diagnostics.heartbeat()` | `uptime_s`, the volatile fields (`bucket`, `whitelisted`, `background_restricted`, `exact_alarms`, `power_save`, `doze`, `interactive`, `locked`, `plugged`, `charging`), `battery_pct`, `battery_temp_c`, `thermal`, `importance`, `mem_avail_mb`, `java_heap_kb`, `native_heap_kb`, `threads`, `since_last_ms` |
| `settings_snapshot` | at start, every 15 min, and on the events below | `reason` (joined with `+`), `sdk_int`, `power{}`, `app{}`, `battery{}`, `bluetooth{}`, `system{}`, `memory{}`, `permissions{granted{},declared[]}`, `vendor{family,settings{}}` |
| `setting_changed` | any volatile field differs from the previous check | `source` (what triggered the check: `heartbeat`, `alarm`, `app_foreground`, `app_background`, `screen_on`, `screen_off`, `power_*`, `doze_changed`, ...), `observed_late_ms` (time since the previous check: how long the change may have gone unseen), `changes{field: [old, new]}`. A change to `bucket`, `whitelisted`, `background_restricted`, `exact_alarms` or `power_save` also takes a full snapshot. |
| `alarm_heartbeat` | slow inexact alarm, about every 15 min | `scheduled_elapsed_ms`, `late_by_ms` (a large value is Doze or standby deferral), `uptime_s`, `process_started_for_alarm` (true: the alarm itself started the process after a kill) |
| `activity` | activity lifecycle | `name`, `state` (`created`, `resumed`, `paused`, `destroyed`), `finishing`, `changing_config` |
| `screen_on`, `screen_off` | broadcasts | |
| `power_connected`, `power_disconnected`, `battery_low`, `battery_okay` | broadcasts | `battery_pct` |
| `power_save_changed` | battery saver toggled | `power_save` |
| `doze_changed`, `light_doze_changed` | Doze state broadcasts | `doze` |
| `bluetooth_state` | adapter state broadcast | `state` |
| `time_changed`, `timezone_changed` | broadcasts | |
| `thermal` | thermal status listener | `status` |
| `network` | default network transport changed (type only) | `transport`, `previous` |
| `trim_memory`, `low_memory` | `ComponentCallbacks2` | `level`, `level_code`, `memory` |
| `app_foreground`, `app_background` | first activity started / last stopped | |
| `clean_shutdown` | `markCleanShutdown` | `reason` |
| `breadcrumb` | `Diagnostics.breadcrumb` | `msg` |
| `listener_unavailable`, `exit_info_error` | a listener or read failed | `listener`, `error` |

The snapshot `app` block holds `standby_bucket`, `standby_bucket_name`, `background_restricted`,
`notifications_enabled`, `can_schedule_exact_alarms` and `process{importance,last_trim_level}`.
`power` holds `ignoring_battery_optimizations`, `power_save_mode`, `device_idle_mode`,
`interactive`, `thermal_status`. Every read is individually guarded: a failing one is the string
`"unreadable"`. The `vendor.settings` map holds whichever of a best-effort key list (OnePlus
startup-list keys, Samsung power-saving keys, generic adaptive-battery keys) are readable without
special permission; absent keys are simply missing. The key lists are unverified guesses on real
devices; a missing key proves nothing.

### Suggested type names for other components

Not enforced; keep them lower_snake_case and keep `diag_heartbeat` for the diagnostics timer.

| type | suggested fields |
|---|---|
| `service_start`, `service_stop` | `reason`, `null_intent`, `foreground_type` |
| `recording_state` | `from`, `to`, `session_id` |
| `ble_connect`, `ble_disconnect`, `ble_reconnect` | `sensor_id`, `status`, `attempt`, `auto_connect` |
| `watchdog_fire`, `watchdog_restart` | `service_running`, `last_heartbeat_age_s` |
| `wakelock` | `action` (acquire/release), `timeout_ms` |

## Battery cost

Diagnostics adds no wake locks, no foreground service and no network, sensor or location use. While
the process is alive it does one cheap read of system flags and one appended line a minute (JVM
timer, which pauses by itself while the CPU sleeps). The only wakeup of its own is the slow alarm:
`setAndAllowWhileIdle` about every 15 minutes, which the system batches and, in Doze, defers to a
maintenance window. That is at most four wakeups an hour, each a few milliseconds of work and one
~300-byte line. A night of events (screen, power, doze, activity, heartbeats, alarms) is about
60-150 KB, so the 20 MB / 14 day cap is never close.

## Run marker and previous-run verdict

`run_marker` is `running` from the start of every process. `process_start.previous_run.verdict` is:

- `first_run`: no earlier diagnostics files and no marker;
- `clean_shutdown`: the marker said `clean`;
- `unclean_exit`: the marker still said `running`: the process died without a clean finish.
  Cross-check with the `exit_info` lines (kills with a reason) and
  `seconds_since_last_heartbeat`. An `unclean_exit` with no matching `exit_info`, a large heartbeat
  gap and `device_rebooted: true` means the phone restarted or lost power.

The marker becomes `clean` when the last visible activity is finished by the user
(`TaigaApplication`) or when something calls `markCleanShutdown`. It goes back to `running` when
an activity is created or something calls `markRunning`. **The recording service must call
`Diagnostics.markRunning()` when it starts**, otherwise a kill of a recording that continued after
the user closed the UI would be reported as clean. Android may also remove a cached process long
after a clean finish; that shows up as an `exit_info` line, not as an unclean verdict.

## Reading kills

- **Killed by the system with a reason**: an `exit_info` line (`reason`, `description`,
  `importance`). OnePlus Smart/Restrict shows `reason=OTHER`, `description=o-kill(6)`.
- **Service stopped silently, process alive** (Samsung Restricted): no `exit_info`; look for the
  heartbeat's `bucket`/`whitelisted` change (`setting_changed`) and the service's own
  `service_stop` line. Heartbeats continue because the process lives.
- **Process killed without exit-info**: `process_start` with `seconds_since_last_heartbeat` much
  larger than 60 and `verdict: unclean_exit`.
- Heartbeats use a JVM timer, which pauses while the CPU is suspended. A gap with
  `elapsed_ms` advancing but no heartbeat during device sleep is not a kill; compare `pid` before
  and after.

## Export and the reader

`Diagnostics.exportZip(context)` fsyncs, then zips the directory to
`cacheDir/diagnostics_<time>.zip` (older exports are deleted) and returns it. There is no UI for it;
a "Share diagnostics" button (settings screen) would call it off the main thread and share the file
through a `FileProvider`.

Pull and read:

```
adb pull /sdcard/Android/data/com.rbmr.taigasleep/files/diagnostics /tmp/diag
python3 tools/read_diagnostics.py /tmp/diag            # --gap 120 --all
```

The reader prints process starts with the previous-run verdict, `KILLED` lines from exit-info,
`CRASH` lines with the top of the stack, `GAP` lines for heartbeat gaps (default over 120 s, noting
a pid change as a suspected kill), `SETTINGS` diffs between consecutive snapshots (battery level,
temperature and memory are ignored), and every other event. Unparsable lines (typically a line cut
off by a kill) are counted and skipped.

## Debug build only

`adb shell am broadcast -a com.rbmr.taigasleep.DEBUG_CRASH` throws a `RuntimeException` on the main
thread, to exercise the crash path.

## Tests

`./gradlew :app:testDebugUnitTest` covers the JSON encoder, rotation/trimming, exit-info dedupe,
previous-run/heartbeat-gap scan, crash-handler chaining, and runs the Python reader on a generated
directory (skipped if `python3` is missing).
