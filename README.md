# CellTracker v0.6.3.1

Signal Trend stability and inspection update.

## Changes
- Signal Trend samples are now stored in MainViewModel/AppState per subscription instead of inside the page Composable.
- SIM1 and SIM2 each keep their own rolling 60-second RSRP/RSRQ/SINR/RSSI history.
- Switching SIM pages, Map/History, or recomposing the cellular page no longer resets the trend.
- Tap or horizontally drag on a trend chart to inspect the nearest sample.
- Selected sample shows timestamp and metric value, with a vertical cursor and point marker.
- Double tap the chart to clear the historical selection and return to live view.

Version: 0.6.3.1 (31)
