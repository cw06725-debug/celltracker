# CellTracker v0.9.3.0.30 — Visual AI Collector

## Why this build
v0.9.3.0.29 can work initially and then stop auto-detecting on some devices. One likely contributor is AccessibilityService screenshot throttling: the previous loop requested screenshots about every 180 ms, which is too aggressive for Android/Samsung implementations and can lead to screenshot interval errors.

## Changes
- Screenshot detector cadence changed from ~180 ms to ~550 ms.
- Keeps PLAY + RECS dual-success criterion.
- Adds Visual AI Collector.
- After each YouTube T0, each successful screenshot saves:
  - PLAYER ROI
  - RECS ROI
- Collector cadence is >=500 ms.
- JPEG work runs on the screenshot executor, not the UI thread.
- Saved under:
  Downloads/CellTracker/VisualAI/<date>/session_HHmmss/attempt_XXX/
- File name example:
  +01234ms_PLAY1_RECS0_PLAYER.jpg
  +01234ms_PLAY1_RECS0_RECS.jpg
- PLAY/RECS in the filename record what the current v0.30 heuristic believed at that moment.
- Manual LOADED remains available.

## What to collect
Run about 10–20 real YouTube loading attempts across good/poor network conditions. Include cases where:
- player starts before recommendations;
- recommendations appear before playback;
- spinner persists;
- page loads normally;
- auto detector misses or times out.

Then zip the corresponding VisualAI session folder and provide it for model/threshold design.
