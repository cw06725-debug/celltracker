# CellTracker v0.9.3.0.36 — Basement Weak Coverage Test MVP

New independent test mode: **Basement Weak Coverage**.

## Workflow
START → B1 → B2 → B1 Return → START → Recovery → Finish

## MVP functions
- Independent Basement test configuration screen.
- Dedicated foreground service + wake lock.
- Dedicated floating controller with one main action button.
- 1 Hz cellular sampling for the selected SIM.
- LTE / NR serving information plus 2G/3G fallback parsing (GSM/WCDMA).
- Automatic events:
  - RAT change
  - Cell change
  - Band change
  - NR Add / Release
  - Data-state change
  - No Service start / end
- Four route segments:
  - START → B1
  - B1 → B2
  - B2 → B1 Return
  - B1 → START Return
- B1 First / B2 / B1 Return:
  - fixed stabilization wait (default 30 s)
  - automatic Ping (default 60 packets / ~60 s)
  - live progress
  - loss / avg / min / max RTT
- START return recovery:
  - LTE recovery
  - real data recovery using Ping
  - 5G/NR recovery
  - 2 consecutive confirmations for stability
  - default max wait 60 s
  - 5G recovery is N/A if NR was not present at the starting point
- Automatic statistics:
  - RAT distribution per segment
  - initial 5G retention
  - first contiguous LTE/4G retention
  - No Service count / total / longest duration
  - LTE/NR signal statistics
  - network transition events
- Reports automatically exported after Finish:
  `Download/CellTracker/Reports/YYYY-MM-DD/Basement/<Operator>_<Device>_<timestamp>/`
  - `summary.html`
  - `summary.csv`
  - `network_raw.csv`
  - `events.csv`
  - `ping.csv`

## Test preparation
- Turn Wi-Fi OFF.
- Make the selected CellTracker SIM the Android default mobile-data SIM.
- Grant Display over other apps for the floating controller.
- Location + Phone permissions are required for Android CellInfo.

## First-version limitations
- RAT/event timestamps retain millisecond timestamps but detection resolution is sampling-bound (~1 s).
- No GPS/map/automatic B1/B2 recognition.
- No adaptive network-stability algorithm yet; fixed wait is used.
- Ping PASS means at least one reply; packet loss is reported separately.
