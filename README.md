# CellTracker v0.4.8

Changes from v0.4.7:
- Live Map SIM switching now uses HorizontalPager so SIM1/SIM2 pages follow the finger continuously.
- Live Map tab taps animate to the selected SIM page.
- LTE SINR adds a compatibility fallback: latest TelephonyManager SignalStrength LTE snapshot, then a conservative vendor/framework string fallback when the public CellInfo getter is unavailable.
- Keeps `--` when no trustworthy SINR is exposed; no synthetic SINR calculation is used.
- Removed `beyondViewportPageCount` so the project remains compatible with the current Compose dependency set.
- versionCode 18 / versionName 0.4.8.
