# CellTracker v0.2.4

Fix release before the map/track milestone.

## Changes
- Recording and UI now share the same latest-location snapshot through `LocationStore`.
- Recording Service keeps location updates active in the background and writes the shared snapshot into every CSV sample.
- Recording panel shows `GPS ready / Waiting for GPS` and last-fix age while recording.
- Single-SIM history records export directly as one CSV.
- Only true dual-SIM recordings show the Combined / Separate-by-SIM export dialog.
- Settings system Back behavior remains fixed from v0.2.3.

## Validation
1. Wait until the home Location card shows latitude/longitude.
2. Start a 10–20 second single-SIM recording.
3. Confirm Recording shows `GPS ready`.
4. Export the record and verify latitude, longitude and `location_valid=true`.
5. Confirm single-SIM Export does not display the dual-SIM export dialog.
