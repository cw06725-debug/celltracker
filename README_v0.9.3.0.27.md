# CellTracker v0.9.3.0.27 — YouTube Auto Detection PoC

## Goal
Reduce operator timing error on YouTube loading tests.

## Manual start + automatic finish
1. START
2. ARMED
3. Tap a YouTube video -> T0
4. CellTracker samples the visible player region about every 150 ms
5. Three consecutive broad-frame motion detections -> automatic T1
6. Delay is saved automatically and the normal return flow continues

## Safety / fallback
- LOADED remains available as a manual T1 fallback.
- AUTO waits at least 500 ms from T0 before completion.
- A single page transition cannot trigger T1; broad motion must repeat across 3 frames.
- Loading-spinner motion should affect too small an area to pass the threshold.
- AUTO gives up after 12 seconds and asks the tester to use LOADED.

## Implementation
- Uses AccessibilityService.takeScreenshot (Android 11+) — no MediaProjection permission is required.
- Only 24 x 14 luminance samples are analyzed per frame.
- Screenshot work runs on a dedicated executor.
- Existing reports use Detection=AUTO_VISUAL or MANUAL_BUTTON.
