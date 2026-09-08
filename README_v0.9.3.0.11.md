# CellTracker v0.9.3.0.11

## Semi-auto YouTube loading fixes

This build focuses on recovery after scrolling and on making RETRY a real state reset.

### 1. Post-scroll tap capture
- Removed the extra 110 ms re-arm delay after a replayed list scroll.
- The transparent touch capture layer is reinstalled immediately after the injected scroll completes.
- Accessibility events are suppressed only while the scroll is actively being replayed, not during an additional settle window.
- This prevents a quick tap immediately after scrolling from opening playback without creating a valid Attempt/T0.

### 2. RETRY hard recovery
- RETRY in Semi-auto now resets unfinished T0/click/gesture/scroll/playback transient state.
- An unfinished Attempt is discarded without affecting already saved rows.
- If RETRY is pressed while YouTube is on a playback page, CellTracker performs exactly one Back and waits for a stable non-playback/list page before re-arming touch capture.
- If automatic return is not confirmed, the tester is asked to press Android Back once; CellTracker then re-arms automatically after the list is stable.
- A generation guard prevents an old scroll replay callback from re-arming stale state after RETRY/STOP.

### Suggested regression sequence
START -> tap video -> LOADED -> return -> scroll -> immediately tap video -> LOADED -> return -> repeat -> RETRY -> tap video.

Expected: scrolling never creates an Attempt; every real video tap creates exactly one Attempt; immediate post-scroll taps are captured; RETRY returns Semi-auto to a clean ready state.
