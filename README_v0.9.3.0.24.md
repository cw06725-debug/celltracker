# CellTracker v0.9.3.0.24

- Unified YouTube + WhatsApp into one Android AccessibilityService / one settings switch.
- YouTube manual START no longer walks the whole accessibility tree.
- After YouTube T0, accessibility events are ignored until LOADED/T1.
- LOADED return uses one Back plus a fixed settle delay; repeated page-tree scans were removed.
- Video title is recovered from the clicked node and its immediate parent/siblings only.
- WhatsApp timing logic/report behavior is preserved inside the unified service.
