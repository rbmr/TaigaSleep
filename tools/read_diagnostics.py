#!/usr/bin/env python3
"""Print a human timeline from a Taiga Sleep diagnostics directory.

Usage: read_diagnostics.py <dir> [--gap SECONDS] [--all]

<dir> is the folder pulled from the phone (diagnostics/ with diag_*.jsonl files), or a
folder of unzipped exports. Stdlib only. See docs/diagnostics.md for the line format.

Prints: process starts (with the verdict about the previous run), kills from ApplicationExitInfo,
crashes, heartbeat gaps longer than --gap seconds (default 120), settings changes between
consecutive settings snapshots, and the other events. --all also prints every heartbeat.
"""

import argparse
import glob
import json
import os
import sys
from datetime import datetime, timezone

# Snapshot fields that change all the time and would drown real changes.
VOLATILE = {
    "_file",
    "t_ms",
    "elapsed_ms",
    "pid",
    "type",
    "reason",
    "battery.level_pct",
    "battery.temperature_c",
    "memory.avail_mb",
    "memory.java_heap_kb",
    "memory.native_heap_kb",
    "memory.threads",
    "app.process.last_trim_level",
}


def load(directory):
    """Return (events sorted by t_ms, number of unparsable lines)."""
    events, bad = [], 0
    for path in sorted(glob.glob(os.path.join(directory, "**", "diag_*.jsonl"), recursive=True)):
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    bad += 1  # usually the line cut off by a kill
                    continue
                if isinstance(event, dict) and "t_ms" in event and "type" in event:
                    event["_file"] = os.path.basename(path)
                    events.append(event)
                else:
                    bad += 1
    # Exit-info lines are logged at the next start; place them where the process actually died.
    events.sort(key=lambda e: (e.get("event_ms", e["t_ms"]) if e["type"] == "exit_info" else e["t_ms"],
                               e.get("elapsed_ms", 0)))
    return events, bad


def stamp(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")


def flatten(value, prefix=""):
    out = {}
    if isinstance(value, dict):
        for key, inner in value.items():
            out.update(flatten(inner, prefix + "." + key if prefix else key))
    elif isinstance(value, list):
        out[prefix] = json.dumps(value, sort_keys=True)
    else:
        out[prefix] = value
    return out


def diff_snapshots(old, new):
    a, b = flatten(old), flatten(new)
    changes = []
    for key in sorted(set(a) | set(b)):
        if key in VOLATILE:
            continue
        if a.get(key) != b.get(key):
            changes.append("%s: %s -> %s" % (key, a.get(key, "(absent)"), b.get(key, "(absent)")))
    return changes


def duration(seconds):
    seconds = int(seconds)
    if seconds < 120:
        return "%ds" % seconds
    if seconds < 7200:
        return "%dm%02ds" % (seconds // 60, seconds % 60)
    return "%dh%02dm" % (seconds // 3600, seconds % 3600 // 60)


def describe(event):
    """One-line text for events without a dedicated formatter."""
    skip = {"t_ms", "elapsed_ms", "pid", "type", "_file"}
    parts = ["%s=%s" % (k, json.dumps(v) if isinstance(v, (dict, list)) else v)
             for k, v in event.items() if k not in skip]
    return " ".join(parts).rstrip()


def timeline(events, gap_seconds, show_all, out):
    last_heartbeat = {}  # pid -> t_ms of that process's latest heartbeat
    last_snapshot = None
    last_hb_any = None
    heartbeats = 0
    for e in events:
        t, kind, pid = e["t_ms"], e["type"], e.get("pid")
        when = stamp(t)

        if kind == "diag_heartbeat":
            heartbeats += 1
            if last_hb_any is not None and (t - last_hb_any["t_ms"]) / 1000 > gap_seconds:
                same = last_hb_any.get("pid") == pid
                out("%s  GAP      no heartbeat for %s (pid %s -> %s)%s" % (
                    when, duration((t - last_hb_any["t_ms"]) / 1000), last_hb_any.get("pid"), pid,
                    "" if same else "  process changed: suspected kill"))
            last_hb_any = e
            last_heartbeat[pid] = t
            if show_all:
                out("%s  heartbeat pid=%s %s" % (when, pid, describe(e)))

        elif kind == "process_start":
            prev = e.get("previous_run", {})
            dev = e.get("device", {})
            out("%s  START    pid=%s v%s%s %s %s Android %s (SDK %s) patch %s" % (
                when, pid, e.get("version_name"), " debug" if e.get("debug") else "",
                dev.get("manufacturer"), dev.get("model"), dev.get("release"), dev.get("sdk"),
                dev.get("security_patch")))
            since = prev.get("seconds_since_last_heartbeat")
            last_state = prev.get("last_state") or {}
            if prev.get("verdict") == "unclean_exit" and last_state:
                out("                    last known state: " + ", ".join(
                    "%s=%s" % (k, v) for k, v in last_state.items()))
            out("                    previous run: %s%s%s" % (
                prev.get("verdict"),
                ", last heartbeat %s before this start" % duration(since) if since is not None else "",
                ", PHONE REBOOTED" if prev.get("device_rebooted") else ""))

        elif kind == "setting_changed":
            late = e.get("observed_late_ms")
            out("%s  CHANGED  %s (seen by %s%s)" % (
                when,
                ", ".join("%s: %s -> %s" % (k, v[0], v[1]) for k, v in e.get("changes", {}).items()),
                e.get("source"),
                ", %s after the previous check" % duration(late / 1000) if late else ""))

        elif kind == "alarm_heartbeat":
            late = (e.get("late_by_ms") or 0) / 1000
            out("%s  ALARM    pid=%s due %s%s%s" % (
                when, pid, "on time" if late < 60 else "LATE by " + duration(late),
                " (Doze/standby deferral)" if late > 300 else "",
                "; process was started for this alarm" if e.get("process_started_for_alarm") else ""))

        elif kind in ("screen_on", "screen_off"):
            out("%s  SCREEN   %s%s" % (when, kind[7:], " (locked)" if e.get("locked") else ""))

        elif kind in ("doze_changed", "light_doze_changed"):
            out("%s  DOZE     %s doze=%s" % (when, "light" if kind.startswith("light") else "deep",
                                           e.get("doze", "?")))

        elif kind in ("power_connected", "power_disconnected"):
            out("%s  POWER    %s battery=%s%%" % (when, kind[6:], e.get("battery_pct")))

        elif kind == "exit_info":
            out("%s  KILLED   %s (code %s) pid=%s importance=%s status=%s desc=%r pss=%sKB rss=%sKB" % (
                stamp(e.get("event_ms", t)), e.get("reason"), e.get("reason_code"), e.get("exit_pid"),
                e.get("importance"), e.get("status"), e.get("description"), e.get("pss_kb"),
                e.get("rss_kb")))
            if e.get("trace"):
                out("                    trace: " + e["trace"].strip().splitlines()[0][:120])

        elif kind == "crash":
            out("%s  CRASH    pid=%s thread=%s %s: %s" % (
                when, pid, e.get("thread"), e.get("exception"), e.get("message")))
            for line in (e.get("stack") or "").splitlines()[1:6]:
                out("                    " + line.strip())

        elif kind == "settings_snapshot":
            if last_snapshot is not None:
                changes = diff_snapshots(last_snapshot, e)
                if changes:
                    out("%s  SETTINGS changed (%s)" % (when, e.get("reason")))
                    for c in changes:
                        out("                    " + c)
            else:
                out("%s  SETTINGS first snapshot (%s)" % (when, e.get("reason")))
            last_snapshot = e

        else:
            out("%s  %-8s pid=%s %s" % (when, kind, pid, describe(e)))

    summary(events, out)
    out("")
    out("%d events, %d heartbeats." % (len(events), heartbeats))


def summary(events, out):
    """Time-weighted share of the observed time in each state, from consecutive heartbeats."""
    beats = [e for e in events if e["type"] == "diag_heartbeat"]
    total = {"observed": 0.0, "screen on": 0.0, "charging": 0.0, "doze": 0.0, "app visible": 0.0}
    for a, b in zip(beats, beats[1:]):
        dt = (b["t_ms"] - a["t_ms"]) / 1000
        if a.get("pid") != b.get("pid") or dt > 300:
            continue  # a kill or a long gap: do not guess what happened in between
        total["observed"] += dt
        for key, field in (("screen on", "interactive"), ("charging", "charging"), ("doze", "doze")):
            if a.get(field) is True:
                total[key] += dt
        if a.get("importance") in ("FOREGROUND", "VISIBLE"):
            total["app visible"] += dt
    kills = {}
    for e in events:
        if e["type"] == "exit_info":
            kills[e.get("reason")] = kills.get(e.get("reason"), 0) + 1
    starts = sum(1 for e in events if e["type"] == "process_start")
    out("")
    out("Summary: %d process starts; kills by reason: %s" % (
        starts, ", ".join("%s x%d" % kv for kv in sorted(kills.items())) or "none"))
    if total["observed"]:
        out("Observed %s between heartbeats: %s" % (duration(total["observed"]), ", ".join(
            "%s %d%%" % (k, 100 * v / total["observed"]) for k, v in total.items() if k != "observed")))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("directory")
    parser.add_argument("--gap", type=float, default=120, help="heartbeat gap to report, seconds")
    parser.add_argument("--all", action="store_true", help="also print every heartbeat")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.directory):
        sys.exit("not a directory: " + args.directory)
    events, bad = load(args.directory)
    if not events:
        sys.exit("no diagnostics lines found in " + args.directory)
    timeline(events, args.gap, args.all, print)
    if bad:
        print("%d unparsable line(s) skipped (a cut-off last line is normal after a kill)." % bad)


if __name__ == "__main__":
    main()
