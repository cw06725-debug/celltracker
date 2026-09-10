# CellTracker v0.9.3.0.39

Basement report refinement + main UI navigation redesign.

## Basement report
- Fixed Points: replaces Packet Loss with Ping Success Rate.
- Per-route network analysis merges RAT distribution + RAT transitions into a single RAT/Band timeline.
- Timeline rows include RAT, start time, end time, duration, share, and Band.
- Band Distribution keeps total duration/share.
- Other Network Events now show start, end, duration, current Band, From and To.
- summary.csv uses Success Rate for fixed-point Ping.

## Selected SIM
- Basement setup now displays `SIM <slot> · <operator>` e.g. `SIM 1 · Ufone`.
- Removes duplicated operator/label output such as `Ufone · Ufone · LTE`.

## Main UI
Adds a floating liquid-glass-inspired bottom navigation:
- 测试
- Cell Info
- Map
- Setting
- Reports

Visual treatment uses translucent rounded surfaces, subtle borders, elevation and selected-state glow.
- Tests are moved out of Cell Info into the Test tab.
- Recording/report history is moved out of Cell Info.
- Reports tab is the central entry for Ping / Basement / YouTube / WhatsApp / Call Setup reports plus Network Recording reports.
