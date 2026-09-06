# CellTracker v0.5.1

Export UX and summary-report update based on v0.5.0.

## Changes

- Export completion is now shown with a dialog instead of a passive path chip.
- The same success dialog is used for exports from the home page and Recording Detail.
- Export dialog actions:
  - Open file: opens the generated HTML summary report.
  - Open folder: opens `Downloads/CellTracker` when the device file manager supports folder URIs, with a Downloads fallback.
  - Close: dismisses the result without further action.
- Raw CSV export behavior is preserved (combined or separate by SIM).
- Every export now also creates `<recording>_summary.html` in `Downloads/CellTracker`.
- Summary report contains:
  - session start/end and sample count
  - issue counts (`Issue × N`)
  - per-SIM operator / RAT / band / average RSRP / RSRQ / SINR / PCI summary
  - DataNet distribution
  - marked issue detail table with time, issue, SIM, operator, RAT, band, RSRP, RSRQ, SINR, PCI, ARFCN, coordinates and note
- Version: 0.5.1 (versionCode 23)

## Notes

The raw CSV remains unchanged for analysis compatibility. The summary is intentionally a separate HTML page so it can be opened directly on Android without adding a large spreadsheet library or changing the CSV schema.
