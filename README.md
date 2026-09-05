# CellTracker v0.2.3

Recording workflow refinement:
- Top SIM tabs are view-only selectors.
- Record scope: Current SIM or Both SIMs; target is locked when recording starts.
- Persistent recording history stored in app external files.
- Per-session Export and Delete, plus Delete all with confirmation.
- Dual-SIM recordings can export separate CSVs or a combined CSV.
- Existing dual-SIM, neighbor-cell, sampling interval and marker settings retained.


## v0.2.3 fixes
- Fixed location capture in RecordingService by registering LocationManager callbacks on the main Looper.
- Added `location_valid` to recorded CSV rows.
- Android system back/back gesture from Settings now returns to the main screen.
