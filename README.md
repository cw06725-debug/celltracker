# CellTracker v0.4.9.2

Hotfix for manual issue marking crash.

- Adds `android.permission.VIBRATE` to the manifest.
- Wraps toast, vibration and sound feedback so feedback failures cannot crash or block event marking.
- Keeps the v0.4.9.1 Mark issue/TestEvent behavior unchanged otherwise.
- Version: 0.4.9.2 (versionCode 21).
