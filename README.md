# CellTracker v0.4.9.1

Hotfix for Mark issue dialog crash. Replaces the scrollable AlertDialog content with a bounded LazyColumn while keeping the same issue-type and note workflow.

# CellTracker v0.4.9

Manual TestEvent / issue marking is now wired into recordings.

## New in v0.4.9
- Mark issue while recording from the Recording card or Live Map.
- Issue type picker uses the customizable Issue Types list from Settings.
- Optional problem description / note.
- Marker captures the selected SIM cellular snapshot and latest GPS position.
- Marker is appended to the active CSV and appears on the live map on the next refresh.
- Marker remains visible in Recording Detail / Map and is clickable for network details.
- CSV adds marker_id and event_source fields for future manual/automatic event unification.
- Existing vibration, toast and sound marker feedback settings are used.

Screenshot capture is intentionally left for the next step because Android MediaProjection needs its own permission/capture lifecycle.

# CellTracker v0.4.8

Changes from v0.4.7:
- Live Map SIM switching now uses HorizontalPager so SIM1/SIM2 pages follow the finger continuously.
- Live Map tab taps animate to the selected SIM page.
- LTE SINR adds a compatibility fallback: latest TelephonyManager SignalStrength LTE snapshot, then a conservative vendor/framework string fallback when the public CellInfo getter is unavailable.
- Keeps `--` when no trustworthy SINR is exposed; no synthetic SINR calculation is used.
- Removed `beyondViewportPageCount` so the project remains compatible with the current Compose dependency set.
- versionCode 18 / versionName 0.4.8.
