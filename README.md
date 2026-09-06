# CellTracker v0.7.0

Floating Window phase 1.

## New
- Recording floating overlay via Android overlay permission.
- Uses the locked Recording Mark Target SIM, not the currently viewed SIM.
- Expanded overlay: SIM/operator/RAT, RSRP, RSRQ, SINR, Band, PCI, DataNet and recording state.
- Compact overlay mode.
- Drag and remember overlay position.
- Background opacity setting (20%-100%); text/buttons remain opaque.
- Auto-show overlay while recording.
- MARK button opens configured Issue Types and writes a normal MANUAL TestEvent through RecordingService.
- Overlay automatically closes when recording stops.
- Settings > Floating Window page with permission shortcut.

## Signal Trend polish
- Removed double-tap gesture dependency that delayed single-tap inspection.
- Tap/drag point selection responds immediately.
- A Live button returns to current-value mode.

## Not in this build yet
- MediaProjection screenshot capture and screenshot-to-marker binding are planned for v0.7.1.
