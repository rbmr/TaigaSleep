# Manifest lines required by diagnostics

The diagnostics branch did not edit `AndroidManifest.xml` (the recording branch owns it). Apply
these by hand when merging.

## `<application>`

```xml
<application
    android:name=".TaigaApplication"
    ...>
```

`TaigaApplication` initializes diagnostics, so without this line nothing is recorded.

## Receiver

```xml
<receiver android:name=".diagnostics.AlarmReceiver" android:exported="false" />
```

The slow alarm heartbeat. Declared in the manifest so a fired alarm can start the process after a
kill, and the log records that it did.

## Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Normal permission (granted at install); only used for the default-network-type listener. If it is
missing, diagnostics logs a `listener_unavailable` line and carries on.

Not needed:

- `PACKAGE_USAGE_STATS`: `UsageStatsManager.getAppStandbyBucket()` for the app's own package works
  without it.
- `BLUETOOTH_CONNECT`: declared by the recording branch. Diagnostics only listens for adapter
  state broadcasts and reads the adapter state in a try/catch, so it works with or without it.
- No `FileProvider` entry: sharing the export zip is another branch's job.

## Debug-only trigger

Nothing in the manifest. The debug build registers a receiver in code for
`com.rbmr.taigasleep.DEBUG_CRASH` (see `TaigaApplication`), so
`adb shell am broadcast -a com.rbmr.taigasleep.DEBUG_CRASH` throws on the main thread. Release
builds never register it.

## Gradle

`app/build.gradle.kts`, for the unit tests:

```kotlin
testImplementation("junit:junit:4.13.2")
```
