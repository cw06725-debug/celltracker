# CellTracker v0.9.3.0.28

## Fix
v0.9.3.0.27 called AccessibilityService.takeScreenshot but the accessibility-service metadata did not declare android:canTakeScreenshot="true". Android therefore refused screenshot capture and AUTO visual T1 could never work.

## Changes
- Added android:canTakeScreenshot="true" to the unified accessibility service.
- Added screenshot failure diagnostics in the floating status text.
- Added a secondary AUTO_AUDIO detector using AudioManager.isMusicActive.
- AUTO_AUDIO requires a real inactive -> active transition after T0 and two consecutive confirmations.
- Relaxed visual thresholds slightly now that actual screenshots are available.
- Manual LOADED remains the final fallback.

## Detection values written to reports
- AUTO_VISUAL
- AUTO_AUDIO
- MANUAL_BUTTON
