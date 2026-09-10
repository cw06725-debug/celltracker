# CellTracker v0.9.3.0.38 — Basement Weak Coverage refinement

Based on v0.9.3.0.37 field-test feedback.

## Floating controller
- Test setup now uses PREPARE FLOATING TEST.
- Preparing does not create T0 or start network logging.
- Floating controller shows START TEST; pressing it creates the session T0 and starts 1 Hz logging.
- Floating window can be dragged by its title area.
- Main action remains a single large button.

## Recovery
- UI label renamed to `Recovery timeout s`.
- This value is only the maximum wait after ARRIVE START.
- LTE/5G Recovery:
  - if already available at ARRIVE START => 0.0 s;
  - otherwise first restored 1 Hz sample timestamp minus ARRIVE START, confirmed by the next consecutive sample.
- Data Recovery:
  - first successful real recovery Ping completion time minus ARRIVE START.
- This fixes the misleading ~1.2 s LTE recovery shown in v0.37 when LTE had never disappeared.

## RAT distribution
- Segment distribution now allocates the entire segment from exact segment start to exact segment end.
- Fixes all-4G segments showing 96–99% instead of 100%.
- HTML now shows 5G / 4G / 3G / 2G / No Service / Other.

## Network Events report
Each Route Segment now has its own analysis block:
- RAT distribution and duration/share
- Band distribution and duration/share
- RAT transition count + From/To + segment elapsed time + timestamp
- Band transition count + From/To + segment elapsed time + timestamp
- Other events (cell/NR/data/no-service)

## Report UX
- After Finish: PREVIEW opens summary.html.
- SHARE / EXPORT shares all report files from the session folder.
- Existing automatic export to Downloads/CellTracker/Reports/... remains.
