# CellTracker v0.2

Android cellular + location drive-test helper.

## v0.2 features
- Dual-SIM tabs based on active subscriptions
- Per-SIM serving cell + neighbor cells
- Neighbor cells inline expand/collapse
- LTE / NR display logic with first-pass NSA indication
- Configurable UI refresh: 0.5 / 1 / 2 / 5 / 10 s
- Configurable recording interval: 0.5 / 1 / 2 / 5 / 10 s
- Foreground recording service
- CSV recording for all active SIMs with GPS data
- Export latest CSV to Downloads/CellTracker
- Settings framework for marker button Tap / Long Press actions and feedback

## Planned next
- v0.3 map track, RAT coloring, GPX, marker points
- v0.4 overlay with configurable fields and opacity
- v0.5 MediaProjection screenshots linked to markers

## Build
GitHub Actions -> Build Android APK -> artifact: CellTracker-debug-apk
