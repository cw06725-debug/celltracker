# CellTracker v0.4.4

Map interaction & stability update.

- Adds Band, bandwidth, RSSI, Timing Advance and NR CSI signal fields where Android exposes them.
- Persists the new cellular fields into new recording CSV files and parses older CSVs compatibly.
- Adds new fields to Settings > Map Point Details.
- Removes duplicate selected-point marker; current location and selection no longer stack duplicate pins.
- Map taps select the nearest sample only within a bounded distance; tapping empty map closes the point inspector.
- Tapping another track location updates the inspector immediately.
- Start/End markers select their exact samples.
- Lighter page transitions to reduce MapView animation jank.
- Backward-compatible GPS validity parsing for older recordings.


## v0.4.4 fixes
- Fixed Recording Detail -> Back crash by preserving the outgoing detail path during AnimatedContent exit.
- Removed manual MapView onDetach during Compose disposal to avoid double-detach edge cases.
- Live point inspector now follows SIM switching and refreshes the current cellular snapshot.
- Operator/SIM are always shown in map point inspector headers.
- Neighbor cells now show Band in both collapsed strongest-cell summary and expanded rows.
