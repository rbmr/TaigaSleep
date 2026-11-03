# Debug build

The debug build (`./gradlew :app:assembleDebug`) installs next to the release app and carries a few
tools for development. Everything debug-only lives in `app/src/debug/`. `app/src/release/` holds
empty twins with the same API, so none of it ends up in the release APK.

| | Release | Debug |
| --- | --- | --- |
| Application id | `com.rbmr.taigasleep` | `com.rbmr.taigasleep.debug` |
| Version name | `x.y.z` | `x.y.z-debug` |
| Launcher name | Taiga Sleep | Taiga Debug |
| Launcher icon | Bear | Bear with an amber `</>` badge, also on themed icons |
| Simulated sensor | Not available | Listed when scanning; streams HR, RR, ECG and ACC without hardware and drops out every few minutes to exercise reconnects |
| Guidance preview | Not available | Long-press "Preventing shutdown" in Settings to preview another brand's guidance |
| Crash trigger | Not available | `adb shell am broadcast -a com.rbmr.taigasleep.DEBUG_CRASH` crashes the app on purpose |
| Diagnostics | `process_start` logs `debug: false` | `process_start` logs `debug: true` |
| Signing | Release key when configured | Debug key |

## Adding a debug-only feature

Put the code in `app/src/debug/` and reach it through `com.rbmr.taigasleep.debug.DebugTools` (or a
file beside it). Give the release twin in `app/src/release/` the same signature with no behaviour.
Then add a row to the table above.
