# CellTracker v0.7.1

Changes in this build:

- Floating Window display fields are now configurable in Settings.
  - Separate field selections for Expanded and Compact modes.
  - Available fields include Mark Target SIM, Operator, RAT, RSRP, RSRQ, SINR, RSSI, Band, PCI, ARFCN, CA/EN-DC, DataNet, Data SIM, Cell ID/NCI, TAC, Speed, GPS Accuracy and Recording status.
- Optional screenshot capture when creating a marker.
  - Uses Android MediaProjection and asks for screen-capture permission when a recording starts.
  - The floating window temporarily hides itself before capture so the screenshot focuses on the tested app.
  - Screenshot path is written into the marker row in CSV and stays associated with the same TestEvent/marker.
- Screenshot naming format:
  - `<TaskName>_<ForegroundApp>_<yyyyMMdd_HHmmss_SSS>.png`
  - Example: `Zong_5G_Video_YouTube_20260906_132512_315.png`
  - Foreground app name uses Android Usage Access when granted; otherwise it falls back to `Screen`.
- Settings includes a shortcut to Usage Access settings for better screenshot app-name detection.
- History marker details can open the captured screenshot from Summary, Map point details, and Samples.
- Export now copies associated screenshots to Downloads/CellTracker and includes them when sharing the export.
- Excel issue details include the screenshot filename.

Version: 0.7.1 (versionCode 33)


## v0.7.1.1
- Added `Include floating window in screenshot` under Settings > Floating Window.
- Default: ON. Marker screenshots keep the overlay and its live network values visible.
- When OFF, the overlay is temporarily hidden during capture, preserving the v0.7.1 clean-screenshot behavior.
- Existing task-name + foreground-app + timestamp screenshot naming is unchanged.


## v0.7.2

- History screenshot experience improved:
  - Recording Summary shows screenshot count.
  - Marker cards show screenshot filename and an in-app thumbnail; tap the thumbnail to open the original image.
  - Marker source (MANUAL/AUTO) is shown in Summary, Map details and Samples.
- Export screenshot association improved:
  - HTML summary shows screenshot count and screenshot filename for each marked issue.
  - Excel issue details now include Source and Screenshot columns.
  - KML issue description includes event Source.
- Added first automated test module: Ping Test (single DUT).
  - Configure host/IP, count, interval, timeout and high-latency threshold.
  - Live statistics: success rate, packet loss, average/min/max, P50/P90/P95.
  - Optional Auto Record starts a CellTracker network recording named Ping_<host> and stops it automatically with the test.
  - High latency creates AUTO / HIGH_PING markers in the recording.
  - Three consecutive failures create an AUTO / PING_TIMEOUT marker.
  - A dedicated Ping CSV is saved under the app's ping_results directory and can be shared from the Ping Test page.

Version: 0.7.2 (versionCode 35)

## v0.7.3

- Floating Window can now control recording directly:
  - START opens a compact overlay setup panel with Task name, Recording scope and SIM/Mark target selection.
  - STOP ends the active recording without returning to CellTracker.
  - Recording status field now shows live elapsed time (REC mm:ss / hh:mm:ss).
- Added `Keep floating window when not recording` under Settings > Floating Window.
  - Default: ON.
  - When enabled, the overlay remains after Stop and can start the next task directly.
  - The last task/scope/target selection is remembered for faster repeated tests.
- Screenshot file naming/export handling improved:
  - Foreground app detection now prefers UsageEvents foreground/resume events instead of only latest usage stats.
  - Exported screenshots are normalized to `<TaskName>_<AppName>_<yyyyMMdd_HHmmss_SSS>.png`.
  - HTML and Excel screenshot labels use the same normalized filename.
- Screen-capture permission is kept alive between overlay-controlled recordings when the floating window is configured to remain active, reducing the need to reopen CellTracker between tasks.

Version: 0.7.3 (versionCode 38)


## v0.7.3.2
- Fixed overlay-started screenshot names falling back to `Untitled`: active task/session metadata is persisted by RecordingService and ScreenCaptureService resolves the task from memory, persisted session metadata, or the recording filename.
- Fixed stale History/Recent recordings after START/STOP from the floating window. MainViewModel refreshes when an externally controlled recording stops, and the UI refreshes recordings again on resume.

## v0.7.3.3
- Fixed the floating window not appearing immediately after the first overlay permission grant. MainActivity now synchronizes the persisted floating-window settings whenever it resumes.
- Improved foreground app detection for screenshot filenames with verified Usage Access, progressively wider UsageEvents windows, a 24-hour UsageStats fallback, launcher-app package visibility, and package-name suffix fallback when an application label cannot be resolved.

## v0.8.0 — Ping Test Phase 2

- Expanded Ping configuration with task name, persistent settings, interval, timeout, high-latency threshold and recording control.
- Added a foreground Ping service with wake lock, live statistics, percentile latency, failure streaks and per-packet cellular/GPS snapshots.
- Added deduplicated AUTO `HIGH_PING` and `PING_TIMEOUT` events linked to the active recording session.
- Added Ping History and detail views for summary, packet samples, route/event map and RTT trend.
- Added CSV, XLSX, HTML Summary and KML exports; CSV/XLSX preserve each Ping sample's network and GPS snapshot.

Version: 0.8.0 (versionCode 42)

## v0.9.0 — Bluetooth Dual-DUT Call Setup

- Added reusable Classic Bluetooth RFCOMM Device Link with Controller/Agent modes, discovery, pairing, connection status, heartbeat, RTT/clock-offset estimation and automatic reconnect.
- Added a framed, versioned JSON message protocol independent of Wi-Fi, cellular data, Internet and cloud services.
- Added dual-DUT MO/MT Call Setup testing for A→B, B→A, bidirectional block and alternating directions.
- Added public-API call-state monitoring, capability-aware auto answer/hang-up and explicit Semi-Auto fallback for restricted Android/ROM builds.
- Requires two-ended confirmation for success and records `MEDIUM_PUBLIC_API` confidence instead of treating a dial request as a successful call.
- Added per-attempt MO/MT timelines, network/GPS snapshots, automatic Recording/TestEvents, live statistics, History, Detail, Map and Trend.
- Added Call Setup CSV, XLSX, HTML and KML exports.

Version: 0.9.0.1 (versionCode 44)


## v0.9.0.1 — Device Link foundation fixes
- Clear filled Local SIM selection, persistent per-SIM phone identities, Save feedback and keyboard dismissal.
- Best-effort public-API phone number detection by selected subscription.
- True Controller ⇄ Agent switching with Bluetooth transport cleanup.
- Agent RFCOMM listener + discoverability flow; Controller paired/nearby device UI and explicit Scan/Pair/Connect flow.
- Expanded Device Link states and permission/Bluetooth/discovery/discoverability diagnostics.
- Android 13+ discovery receiver exported for Bluetooth framework broadcasts.

## v0.9.0.3
- Device Link Agent controls use full-width buttons so labels do not wrap/crowd around the Android discoverability confirmation dialog.
- Phone number Auto Detect now tries SubscriptionManager.getPhoneNumber, SubscriptionInfo.number, and per-subscription TelephonyManager.line1Number. Manual entry remains the fallback when the OEM/carrier does not expose a line number through public APIs.
- MT auto-answer retries TelecomManager.acceptRingingCall over a short bounded window because some OEM dialers announce RINGING before Telecom is ready. The UI reports when the public API is blocked and manual answer is required.
- Call Setup history loading moved off the main/UI thread, including initial load, refresh, and post-test refresh, to avoid ANR on sessions with larger CSV files.
