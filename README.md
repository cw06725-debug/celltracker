# CellTracker v0.2.1

Changes from v0.2.0:

- Neighbor Cells UI redesigned.
  - Collapsed state shows detected count and strongest neighbor summary.
  - Expanded state uses compact per-cell layout.
  - Neighbors are sorted by RSRP from strongest to weakest.
- Recording card now has SIM 1 / SIM 2 tabs and per-SIM sample counts.
- Dual-SIM recording still samples both SIMs in parallel.
- CSV export now offers:
  - Separate by SIM (default/recommended): creates one CSV per SIM.
  - Combined: exports the original combined dual-SIM CSV.
- Recording loop compensates for cellular-read processing time so the requested interval is treated as the target cycle period.
- Version bumped to 0.2.1.

Build with GitHub Actions as before. Replace the existing project contents, then run:

```bash
git add .
git commit -m "CellTracker v0.2.1 neighbor and dual SIM export"
git push
```
