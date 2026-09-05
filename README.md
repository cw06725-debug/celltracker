# CellTracker v0.3.3

Fixes:
- App title now reads BuildConfig.VERSION_NAME instead of a hard-coded old version.
- OSM raster tiles use the canonical https://tile.openstreetmap.org/ endpoint.
- App-specific User-Agent is initialized before MapView creation.
- Visible OpenStreetMap attribution added.
- Map sample list/effect keys are stabilized to stop repeated camera refits and UI jitter.
- Initial bounds fit is non-animated.

# CellTracker v0.3.1

Fix release for the historical track map.

- Uses an explicit HTTPS OpenStreetMap tile source.
- Initializes osmdroid configuration/cache before creating the map.
- Removes camera/overlay mutations from AndroidView.update to stop repeated relayout/refit jitter.
- Fits the camera only when the selected sample set changes.

# CellTracker v0.3.0

V0.3 introduces recording history preview and the first in-app map workflow.

## New in 0.3.0
- Tap a Recent recording card to open Recording Detail.
- Detail tabs: Summary / Map / Samples.
- Historical GPS track on an OpenStreetMap-based map.
- Track segments are colored by RAT (LTE, 5G NSA, 5G NR, 3G, 2G).
- Dual-SIM history can be filtered by Both / SIM 1 / SIM 2.
- Samples tab shows timestamp, SIM, RAT, PCI, ARFCN, signal and coordinates.
- Summary shows session duration, GPS coverage, RAT counts and RSRP statistics.
- Existing CSV marker fields are parsed and reserved for the upcoming marker feature; marker rows will appear on the map when present.
- Existing export/delete behavior remains available from both Home and Recording Detail.

## Map notes
- Map tiles require an internet connection.
- The map uses OpenStreetMap tiles through osmdroid and does not require a Google Maps API key.
- Historical tracks remain stored in the app recording CSV files; the map reads those saved files directly.

## Build
Push this project to GitHub and use the included `Build Android APK` workflow, or build the Debug APK in Android Studio.


## v0.3.2
- Fixed map layout jitter by constraining the detail content and map to the remaining height with Compose weight.
- Switched tile source to osmdroid TileSourceFactory.MAPNIK.
- Kept map camera/overlay updates outside AndroidView.update.
