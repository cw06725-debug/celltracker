# CellTracker v0.9.3.0.29 — YouTube success = Playback + Recommendations

User-defined success criterion:
1. The selected video is playing normally.
2. The recommendation list under the video has loaded normally.

T1 is recorded only when BOTH conditions are true.

## Detection
- PLAY ready: AUTO_VISUAL or AUTO_AUDIO.
- RECS ready: bounded Accessibility scan of the lower part of the YouTube page.
  It requires multiple visible text labels, longer title-like labels and clickable cards.
- Two consecutive RECS confirmations are required.
- Final report detection is AUTO_VISUAL+RECS or AUTO_AUDIO+RECS.
- Floating status shows PLAY✓/PLAY… and RECS✓/RECS… independently.
- Manual LOADED remains a fallback.
- AUTO timeout increased to 15 s.

This prevents a video starting early from being counted as successful while the rest of the watch page is still blank/loading.
