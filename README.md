# CellTracker 0.1

Minimal Android cellular + GPS viewer.

## Features
- LTE / NR serving-cell detection
- MCC, MNC, TAC, Cell ID/NCI, PCI, EARFCN/NR-ARFCN
- LTE RSRP/RSRQ/RSSNR and NR SS-RSRP/SS-RSRQ/SS-SINR
- GPS/network location: latitude, longitude, altitude, accuracy, speed, bearing
- Refreshes cellular data about every 2 seconds
- Shows count of non-registered LTE/NR cells returned by Android

## Build APK with GitHub Actions
1. Create a GitHub repository.
2. Upload all files in this project, including `.github/workflows/build-apk.yml`.
3. Push to `main` or `master`, or open **Actions > Build Android APK > Run workflow**.
4. Open the finished workflow run and download artifact **CellTracker-debug-apk**.
5. Extract it and install `app-debug.apk`.

## Notes
- Grant precise location permission. Android gates cell information behind location permission.
- Some OEMs/modems hide or sanitize individual fields.
- In NSA 5G, Android may expose LTE and NR in device/vendor-dependent ways. This v0.1 simply prioritizes a cell marked `isRegistered`.
- Debug APK uses the standard Android debug signature and is intended for testing only.
