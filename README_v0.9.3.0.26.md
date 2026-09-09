# CellTracker v0.9.3.0.26

- Restores YouTube manual T0 detection on builds that do not emit TYPE_VIEW_CLICKED.
- T0 sources: ACCESSIBILITY_CLICK, WINDOW_CHANGE fallback, UI_CHANGE fallback.
- Fallback does not walk the full Accessibility tree.
- Fixes 1970-01-01 report folders by preserving the real test start time.
- Old YouTube records with started=0 now fall back to first sample/file time.
- Report folders remain Downloads/CellTracker/YYYY-MM-DD/YouTube and /WhatsApp.
