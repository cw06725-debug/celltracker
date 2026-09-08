# CellTracker v0.9.3.0.13

## Semi-auto START arming reliability fix

This build fixes the second-sample failure seen in v0.9.3.0.12.

### Changes
- First and subsequent samples now use the same safe START arming path.
- START no longer installs the full-screen touch capture overlay inside the same click transaction.
- New state flow: `START -> ARMING... -> ARMED -> ACTIVE -> START`.
- A 320 ms settle window isolates the START ACTION_UP/window re-layout from YouTube, preventing the START gesture from falling through to the mini-player.
- `ARMED` is shown only after the touch capture overlay is actually installed.
- If capture installation fails, the UI returns to `START` and asks the tester to retry instead of exposing a false ARMED state.
- LOADED / cancelled attempt / RETRY restore START to an enabled clean idle state.

### Recommended validation
Repeat at least 10 cycles:
1. Navigate/scroll YouTube freely.
2. Tap START.
3. Wait until button changes from ARMING... to ARMED.
4. Tap one target video.
5. Tap LOADED at first frame.
6. Return to the list, scroll, and repeat.

Expected: START itself never opens the YouTube mini-player; every ARMED state has a real capture layer; one START produces at most one T0.

Version: 0.9.3.0.13
VersionCode: 89
