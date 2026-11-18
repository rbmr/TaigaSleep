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
  fsynced every 30 s when something was appended since the last sync, and immediately after crash
  lines and `logSync`.
- Writes happen on one daemon thread (`diagnostics`). Callers never block on disk.
- **Size cap**: at most 20 MB in total and 14 days of files. Trimming (oldest files first, never
  the file being written) runs at startup, every hour and whenever a file rolls over, on the
  diagnostics thread.
- Other files in the directory: `run_marker` (`state=running|clean`), `exit_info_seen` (dedupe
  keys for exit-info entries). They are not trimmed and are tiny.
- Cost: one line per minute (about 450 bytes while idle; about 720 while a recording runs, because
  of the `cost` block), a settings snapshot (about 2 KB) every 15 minutes and on changes, plus
  events. Roughly 1 MB per day, plus about 130 KB for a night of recording cost.

## Privacy

Recorded: device identity, app version, OS settings that affect background survival, battery of
the phone, memory, stack traces, kill reasons, and what recording costs the phone (CPU, wake lock,
its own file writes and its own uid's traffic). Never recorded: heart-rate or any other health
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
| `process_start` | every process start | `process_start_ms`, `start_elapsed_ms`, `process_name`, `debug`, `build_type`, `git_commit`, `version_name`, `version_code`, `device{manufacturer,brand,model,device,sdk,release,security_patch,fingerprint}`, `previous_run{verdict,marker,pid,file,last_event_ms,last_event_type,last_heartbeat_ms,seconds_since_last_heartbeat,device_rebooted,last_state{uptime_s,bucket,whitelisted,background_restricted,power_save,doze,interactive,locked,plugged,charging,battery_pct,importance}}` |
| `exit_info` | once per `ApplicationExitInfo` entry not logged before (dedupe by timestamp+pid, persisted) | `event_ms` (when the process died), `exit_pid`, `reason`, `reason_code`, `status`, `importance`, `description` (e.g. `o-kill(6)`), `pss_kb`, `rss_kb`, `trace` (ANR/crash only, first 4 KB) |
| `crash` | uncaught exception (then chains to the previous handler) | `thread`, `exception`, `message`, `stack` (with causes), `memory` |
| `diag_heartbeat` | every 60 s, and `Diagnostics.heartbeat()` | `uptime_s`, the volatile fields (`bucket`, `whitelisted`, `background_restricted`, `exact_alarms`, `power_save`, `doze`, `interactive`, `locked`, `plugged`, `charging`), `battery_pct`, `battery_temp_c`, `thermal`, `importance`, `mem_avail_mb`, `java_heap_kb`, `native_heap_kb`, `threads`, `since_last_ms`, and while a recording runs `cost` (see "The cost of a recording") |
| `settings_snapshot` | at start, every 15 min, and when an event changes one of its fields: `power_save_changed`, `doze_changed`, `bluetooth_state`, `thermal`, `power_connected`, `power_disconnected`, `battery_low`, `battery_okay`, `timezone_changed` | `reason` (joined with `+`), `sdk_int`, `power{}`, `app{}`, `battery{}`, `bluetooth{}`, `system{}`, `memory{}`, `permissions{granted{},declared[]}`, `vendor{family,settings{}}` |
| `setting_changed` | any volatile field differs from the previous check | `source` (what triggered the check: `heartbeat`, `alarm`, `app_foreground`, `app_background`, `screen_on`, `screen_off`, `power_*`, `doze_changed`, ...), `observed_late_ms` (time since the previous check: how long the change may have gone unseen), `changes{field: [old, new]}`. A change to `bucket`, `whitelisted`, `background_restricted`, `exact_alarms` or `power_save` also takes a full snapshot. |
| `alarm_heartbeat` | slow inexact alarm, about every 15 min while the run is marked running | `scheduled_elapsed_ms`, `late_by_ms` (a large value is Doze or standby deferral), `uptime_s`, `process_started_for_alarm` (true: the alarm itself started the process after a kill) |
| `activity` | activity lifecycle | `name`, `state` (`created`, `resumed`, `paused`, `destroyed`), `finishing`, `changing_config` |
| `screen_on`, `screen_off` | broadcasts | |
| `power_connected`, `power_disconnected`, `battery_low`, `battery_okay` | broadcasts | `battery_pct` |
| `power_save_changed` | battery saver toggled | `power_save` |
| `doze_changed`, `light_doze_changed` | Doze state broadcasts | `doze` |
| `bluetooth_state` | adapter state broadcast | `state` |
| `time_changed`, `timezone_changed` | broadcasts | |
| `thermal` | thermal status listener | `status` |
| `recording_cost` | a recording stopped, or was picked up after a kill | see "The cost of a recording" |
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
| `service_start`, `service_stop` | `reason`, `null_intent`, `foreground_type`, `foreground` |
| `recording_state` | `from`, `to`, `session_id` |
| `ble_connect`, `ble_disconnect`, `ble_reconnect` | `sensor_id`, `status`, `attempt`, `auto_connect` |
| `watchdog_fire`, `watchdog_restart` | `action`, `service_running`, `last_heartbeat_age_s` |
| `boot_resume`, `resume_refused`, `service_foreground_refused` | `recording`, `error` |
| `wake_trigger` | `recording`, `at_utc_ms` |
| `wake_alarm`, `wake_rang`, `wake_alarm_failed`, `wake_alarm_stopped` | see "The wake-up alarm" below |
| `wake_change` | `recording`, `start_utc_ms`, `end_utc_ms` (empty when the alarm was switched off) |
| `wakelock` | `action` (acquire/release), `timeout_ms` |

## The wake-up alarm

The ringing service starts the alarm on the alarm audio stream, independent of the ringer's silent
mode. Whether it was heard is what these events answer.

`wake_alarm` is written when the ring starts: `source` (`signal`, `window_end` or `test`), `reason`
(`signal` or `window_end`, the same words the notification uses), `ringer_mode` (`silent`, `vibrate`,
`normal` or `unknown`), `dnd_filter` (`all`, `priority`, `none`, `alarms` or `unknown`), the alarm
stream's `alarm_volume_before`, `alarm_volume_after` and `alarm_volume_max`, `playback_started`,
`sound` (`user_ringtone`, `default_alarm` or `bundled_tone`), `audio_focus`, `notification_posted` and
`vibration_started`. The stream is raised to 70 % of its maximum when it was below that, and the
raise is only ever upward.

`wake_rang` is written only when the sound actually started, with the same `source` and `reason` and
the `volume` and `volume_max` it played at. A ring that produced no sound writes
`wake_alarm_failed` instead, with the `reason` (`no sound could be played`, the foreground service
was refused, or the service could not be started), the ringer mode, the Do Not Disturb filter and
whether notifications are allowed.

`wake_alarm_stopped` is written when Stop ends the alarm: `how` (`user`, `service` or `test`),
`rang_ms` (how long it rang) and `volume_restored` (whether the stream was put back). A test ring
reports the same `playback_started` and volume through the alarm's settings.

The start of a recording and the alarm's own settings warn when Do Not Disturb is set to total
silence, notifications are off or the phone has no audio output. Those are the phone's choices and
are reported, not overridden.

## The cost of a recording

Streaming a night must not be a guess: every night records what it cost the phone. The counters are
plain in-memory values (a recording's process keeps no cost state on disk), so they add no wake-up,
no timer, no permission and no fsync of their own.

### The `cost` block of the heartbeat

While a recording runs in this process, the one-minute `diag_heartbeat` gains one `cost` object.
Without a recording the object is absent and nothing new is read. The keys are short because the
block has to stay inside its size budget; their order is part of the format:

| key | meaning |
|---|---|
| `c` | remaining charge in microamp-hours (`BATTERY_PROPERTY_CHARGE_COUNTER`); absent where the phone does not report it |
| `i` | current in microamps (`BATTERY_PROPERTY_CURRENT_NOW`); absent where the phone does not report it |
| `e` | remaining energy in nanowatt-hours (`BATTERY_PROPERTY_ENERGY_COUNTER`); absent where the phone does not report it |
| `p` | `[process CPU ms, own thread CPU ms]`: cumulative `Process.getElapsedCpuTime()`, and the diagnostics thread's own share; the difference is every other thread of the process |
| `w` | `[wake lock held ms, acquisitions]` for this recording |
| `t` | `[tick-loop iterations, diagnostics timer firings, heartbeats written, recording preference writes]` |
| `o` | `[recording-file bytes, recording-file writes, event-log bytes, event-log writes, diagnostics bytes, diagnostics writes, fsyncs]` |
| `b` | notifications and payload bytes per declared stream: `{"<stream id>": [notifications, bytes], ...}` for every stream that delivered frames; each frame is one notification and its payload is exactly the bytes the tracker sent, whatever streams the driver offers |
| `n` | `[TX bytes, RX bytes]` from `TrafficStats` for the app's uid, since the recording began |
| `g` | `[garbage collection count, garbage collection time ms]` from `Debug.getRuntimeStat` |
| `m` | `[screen-on ms, doze ms]` during the recording |
| `h` | thermal headroom (`PowerManager.getThermalHeadroom`), where available |
| `u` | microseconds spent collecting this block |

The rest of the phone's state stays in the heartbeat's own fields: `plugged`, `charging`,
`interactive`, `doze`, `bucket` and `thermal` are read by the same line and keep their names, and
so do `threads`, `java_heap_kb` and `native_heap_kb`; the `cost` block only adds what they do not
say, which is the garbage collector's own count and time.

### The `recording_cost` event

It is written when this process ends a recording, and when it picks up a recording whose process
died. A killed process writes nothing at the moment it dies; the reading tool measures that segment
from the heartbeats it left. The fields:

| field | meaning |
|---|---|
| `recording`, `reason` | the recording's id and why it ended: `stopped`, `forced_stop`, `interrupted`, `discarded`, `fetch_pending`, or `recovered` for a recording picked up after a kill |
| `started_utc_ms`, `duration_s` | the recording's start and its whole duration, including a segment measured by an earlier process |
| `segment_s` | the part of the duration this process measured; its counters cover only that part |
| `battery_pct_start`, `battery_pct_end`, `charge_uah_start`, `charge_uah_end` | the phone's battery at the start of the segment and at its end |
| `charging_s`, `charging_end` | how much of the segment was spent charging, and whether it was charging at the end; a charging night is flagged, its charging intervals are not averaged into drain |
| `screen_on_s`, `screen_off_s`, `doze_s` | how the segment's time was spent |
| `wake_lock_ms`, `wake_lock_n` | wake lock held and how often it was taken |
| `cpu_ms`, `cpu_ms_per_h` | this process's CPU during the segment, in total and per hour |
| `bytes_written`, `event_bytes`, `diag_bytes`, `fsyncs` | what recording wrote, and how many fsyncs it cost |
| `stream_stalls`, `reconnects`, `watchdog_restarts` | what the recording had to recover from |
| `drain_pct_per_h_on`, `drain_pct_per_h_off` | battery percentage dropped per hour, split by whether the screen was on; null when nothing was measured in that state |
| `drain_uah_per_h_on`, `drain_uah_per_h_off` | the same split from the charge counter, which is far finer than the percentage |
| `ticks`, `timers`, `heartbeats` | the tick loop, the diagnostics timers and the heartbeat lines of the segment |
| `net` | traffic since the recording began |

The battery percentages and the charging flag come from the same sticky battery intent as the
heartbeat's `battery_pct` and `charging`, so a segment's ends and its heartbeats never disagree
about the phone's charge.

The `recovered` line marks where a new segment starts: it carries the time the recording had
already run and the readings at recovery. It deliberately has no wake-lock or CPU figures, because
the process that measured them is gone.

## Battery cost

Diagnostics adds no wake locks, no foreground service and no network, sensor or location use. While
the process is alive it does one cheap read of system flags and one appended line a minute (JVM
timer, which pauses by itself while the CPU sleeps). The only wakeup of its own is the slow alarm:
`setAndAllowWhileIdle` about every 15 minutes while the run is marked running, which the system
batches and, in Doze, defers to a maintenance window. `markRunning` schedules it and
`markCleanShutdown` cancels it; a recording picked up by a new process marks the run running again,
so the alarm comes back only when something is there to keep alive. An alarm that fires where
nothing is marked running is not scheduled again, so one left behind stops after that wakeup. A
process start alone arms nothing: no alarm cold-starts the app after a clean finish, where the start
would mark the run running and turn a later freeze into an unclean exit. At most four wakeups an
hour, each a few milliseconds of work and one ~300-byte line. A night of events (screen, power,
doze, activity, heartbeats, alarms) is about 60-150 KB, so the 20 MB / 14 day cap is never close.

The cost of a recording is measured by the diagnostics themselves, so it is bounded and visible.
While a recording runs, the heartbeat's `cost` block adds 268 bytes on a phone that reports no
energy counter and 287 bytes when every optional reading is present; the unit test measures both
and holds them to 270 and 295 bytes, and the 20 MB / 14 day cap has room for either. Collecting it
reads the battery, the process clocks and the traffic counters once a minute: the unit test asserts
that one collection costs less than 5 ms, and every heartbeat reports the collection it actually
measured in `cost.u`, so the real figure on a phone is in the log rather than guessed here. Without
a recording the block is absent and nothing new is read.

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

`markRunning` also schedules the slow alarm and `markCleanShutdown` cancels it, so a process start
alone leaves no wakeup behind.

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

## Reading the directory

Pull and read:

```
adb pull /sdcard/Android/data/com.rbmr.taigasleep/files/diagnostics /tmp/diag
python3 tools/read_diagnostics.py /tmp/diag            # --gap 120 --all
```

The reader prints process starts with the previous-run verdict, `KILLED` lines from exit-info,
`CRASH` lines with the top of the stack, `GAP` lines for heartbeat gaps (default over 120 s, noting
a pid change as a suspected kill), `SETTINGS` diffs between consecutive snapshots (battery level,
temperature and memory are ignored), `COST` lines for finished recordings, a per-recording cost
table, and every other event. Unparsable lines (typically a line cut off by a kill) are counted and
skipped.

The cost table has one row per recording: how it ended, drain per hour split by screen state, wake
lock and CPU per hour, bytes written and time in Doze. A recording that was killed has no stop line;
its row is measured from the first and last heartbeat it left, and its drain and charge columns say
`(all)` because the heartbeats do not split it by screen state. A segment that is still the last
thing in the log, with no later process start or exit-info line, is marked `unfinished` instead of
`killed`. The last line compares the two most recent finished recordings, which is what a change to
the recording path is judged by.

## Debug build only

`adb shell am broadcast -a com.rbmr.taigasleep.DEBUG_CRASH` throws a `RuntimeException` on the main
thread, to exercise the crash path.

## Tests

`./gradlew :app:testDebugUnitTest` covers the JSON encoder, rotation/trimming, exit-info dedupe,
previous-run/heartbeat-gap scan, crash-handler chaining, the cost block and its size and time
budgets, the `recording_cost` event for a stop, a charging night and a recovery after a kill, the
alarm following the run marker, a heartbeat's single battery read, one fsync per appended batch,
and which broadcasts take a snapshot, and runs the Python reader on a generated directory (skipped
if `python3` is missing), including its cost table for a killed recording.
