# CellTracker v0.6.0

- Slower, smoother tap animation for SIM/page switching while keeping swipe finger-following.
- Recording History: clickable marker details in Summary, All/Markers map filter, orange issue markers, Samples marker -> Map focus.
- Export: CSV + HTML Summary + XLSX + KML track/issue file.
- Home signal trend chart for RSRP/RSRQ/SINR/RSSI over the latest 1 minute.
- Added best-effort CA/EN-DC information plus CQI, signal level, ASU, per-SIM Data RAT, Voice RAT and roaming.

CA/EN-DC availability depends on what Android/vendor radio APIs expose. LTE CA is only labeled active when reported by the framework or multiple registered LTE component carriers are visible; otherwise the app shows -- rather than inventing a CA combination.
