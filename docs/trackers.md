# Recordings with several trackers

Status: design. Today a recording is one run of one tracker. This document describes where the app
goes next: a recording made by zero to many trackers, where the phone itself can be one of them. It
says what changes, what stays, and the order of the steps. Each step is shippable on its own; none
of them is implemented by this document.

## Why

- The phone has sensors of its own. Its microphone can record sound (snoring, talking, noise) next
  to whatever a strap records, and a night recorded with the phone alone is useful too.
- People own more than one tracker: a chest strap for heart rate and a ring or band for movement.
- Today the phone's two roles are mixed. It receives and stores what the tracker sends, and the
  start flow, the smart alarm and preventing shutdown each assume exactly one connected tracker.

## Two roles of the phone

**The recorder** is the phone as receiver and store. It owns the recording: the foreground service,
the state machine (start, stop, force stop, discard), the writer and the store, storage checks,
the watchdog, upload and the alarm's ringing. It knows nothing about any device. It talks to
sources only through the `Tracker` interface and writes frames to logs named by the recording's
stream list.

**A tracker** is a source of streams. A Bluetooth strap is one. The phone is one too: the **phone
tracker** offers the streams the phone itself can record, starting with sound. It is always
present, needs no connection, reports no battery or firmware, and needs runtime permissions
instead (the microphone). The recorder treats it like any other tracker.

A **participant** is one tracker taking part in one recording: the tracker, the streams chosen for
it and its recording mode. A recording has one or more participants.

## What a recording holds

`meta.json` gains a `trackers` list and each stream names its tracker. The format stays version 1:
every key is added, none is removed, and a recording written before has one implicit participant.

```json
{
  "mode": "stream",
  "sensor": { "kind": "polar-h10", "id": "069A4737", "name": "Polar H10 069A4737" },
  "trackers": [
    { "ref": "t0", "kind": "polar-h10", "id": "069A4737", "name": "Polar H10 069A4737",
      "mode": "stream", "firmware": "3.0.35", "battery_percent_at_start": 87 },
    { "ref": "phone", "kind": "phone", "id": "phone", "name": "Phone", "mode": "stream" }
  ],
  "streams": [
    { "id": "hr", "tracker": "t0", "type": "heart_rate", "codec": "polar-hr-v1", "file": "hr.log" },
    { "id": "sound", "tracker": "phone", "type": "sound", "codec": "opus-v1", "file": "sound.log" }
  ]
}
```

- `sensor` stays and names the first external tracker, so an older reader and the server keep
  working. A recording with only the phone has `sensor` set to the phone tracker.
- The first external tracker keeps today's file names. A later tracker whose file name is taken gets
  its `ref` as a prefix (`t1-acc.log`). The frame log header already carries the stream type, so
  nothing inside a log changes.
- The recording's `mode` is `stream` when any participant streams, otherwise `on_device`. Each
  participant has its own `mode`.
- A stream without `tracker` belongs to the first participant.

The server stores bytes and does not change. The worker reads `trackers` and, when two
participants offer the same stream type, uses the first participant's stream unless an analysis
asks for another; the analysis result records which one it read.

## Start flow

1. **Trackers.** The picker lists the phone and the trackers in range. The user selects one or
   more and the external ones connect. Each connected tracker is a row with its own status and its
   own Disconnect.
2. **Configure.** One section per participant (the phone's section is Sound and later other phone
   data), each with its data choices and, where the tracker offers both, its mode. A night section
   holds the smart alarm.
3. **Preventing shutdown** is asked for when any participant streams and the phone needs it.
4. **Start** is possible when at least one participant has at least one stream chosen.

Until the last step below is done, a recording still needs one connected external tracker, and the
phone tracker can only be added to it.

## During a recording

- Each participant has its own link and its own recovery ladder (stall detection, restarting its
  streams, reconnecting). Losing one tracker never ends the recording; the recording state lists
  every participant's state, and the screen and the notification show the worst one with its name.
- A step that affects every Bluetooth tracker (turning the Bluetooth stack off and on) is taken by
  the recorder once, for all of them, and never while another participant's step is in progress.
- On-device participants are fetched at Stop, one after the other; a fetch that fails leaves that
  participant `fetch_pending` while the other participants' data is final.
- Storage checks add up every participant's streams.

## Smart alarm

The alarm decision reads signals, not trackers. Movement can come from any participant's
accelerometer and heart rate from any participant's heart rate stream; later, sound can be a
signal too. When more than one participant offers a signal, the first one is used and the user can
pick another in the alarm settings. The alarm is offered when at least one streaming participant
provides a signal, and on-device participants never feed it.

## Steps

Each step is one issue and one pull request, merged before the next starts.

1. **Sound from the phone** (#357). The microphone capture is its own source that writes through the
   recorder's stream interface, not part of a tracker driver. Shown as its own section in Configure.
2. **Recorder and participants.** The recording service holds a list of participants instead of one
   tracker; link, stall detection and recovery move into a per-participant object. With one
   tracker nothing visible changes. Sound becomes the phone participant.
3. **Several trackers in meta.json.** Write `trackers` and each stream's `tracker`; the reader, the
   export and the worker understand it; old recordings read as before.
4. **The phone tracker.** `PhoneTracker` implements `Tracker` and offers sound. Start is allowed with
   the phone alone. The picker lists the phone.
5. **Several external trackers.** The tracker hub holds a set of connected trackers; the picker
   selects several; Configure gets one section per tracker; the recording state and notification
   show each participant.
6. **Alarm and preventing shutdown across participants.** Signals from any participant, a choice
   when there are several; preventing shutdown when any participant streams.
7. **Worker input choice.** When two participants offer the same stream type, an analysis can name
   the one it reads, and the result records it.

## Not decided here

- Which other phone sensors become streams (accelerometer on the mattress, light).
- Sound analysis on the server.
- Synchronising the clocks of several trackers beyond the phone timestamps every frame already
  carries.
