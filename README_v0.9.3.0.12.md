# CellTracker v0.9.3.0.12

## Semi-auto manual-per-attempt workflow

This version changes Semi-auto Video Loading testing to an explicit per-sample START/LOADED workflow.

### New workflow
1. Open the desired YouTube video list and scroll/navigate freely.
2. Press **START** in the CellTracker overlay.
3. CellTracker arms touch capture only for the next sample.
4. Tap one YouTube video. The real captured tap is **T0**.
5. When loading is complete, press **LOADED**. The LOADED press time is **T2**.
6. CellTracker calculates `Loading Delay = T2 - T0`, saves the row, and returns from playback.
7. Touch capture remains OFF. Scroll to the next desired item freely.
8. Press **START** again for the next sample.

### Important behavior changes
- No START = no touch capture and no new Attempt.
- Scrolling between tests can never create an Attempt because the capture layer is not armed.
- Page-transition-only fallback T0 is disabled; T0 must come from a real captured click/touch.
- Android Back is no longer accepted as T2. Returning before LOADED cancels the unfinished sample.
- LOADED with no valid T0 shows a prompt to press START and tap a video first.
- Existing Auto mode behavior is unchanged.

Version: 0.9.3.0.12
Version code: 88
