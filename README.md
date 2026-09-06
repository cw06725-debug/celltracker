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
