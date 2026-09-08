# CellTracker v0.9.3.0.21

## WhatsApp overlay
- STOP is smaller and aligned to the right.
- STOP requires a second tap within 4 seconds to confirm, reducing accidental termination.

## YouTube manual timing
- Semi-auto now uses the same interaction model as WhatsApp.
- START -> ARMED -> native YouTube tap records T0 -> LOADED records T1.
- The full-screen touch-capture overlay is no longer installed in manual YouTube mode.
- Preferred T0 source is TYPE_VIEW_CLICKED; list-to-playback UI transition is used only as fallback.
- STOP is smaller and requires a second confirmation tap.
- Existing report/history/export and automatic return behavior are retained.
