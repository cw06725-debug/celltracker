# CellTracker v0.9.3.0.35

Fixes Visual AI data visibility/export.

Why v0.34 looked empty in Downloads:
- Collection succeeded (frame count increased), but frames were intentionally stored in app-private external storage:
  `/storage/emulated/0/Android/data/com.example.celltracker/files/VisualAI/...`
- Modern Android file managers often hide/restrict Android/data.
- Therefore `Downloads/CellTracker` stayed empty.

v0.35 behavior:
- Keep collecting into app-private storage for performance.
- On STOP COLLECTION, automatically ZIP the entire session.
- Export the ZIP via MediaStore to:
  `Downloads/CellTracker/VisualAI/<date>/CellTracker_VisualAI_<date>_<time>.zip`
- UI shows EXPORTING / EXPORTED / EXPORT_FAILED and the public ZIP path.
- ZIP contains all PLAYER/RECS JPEGs plus labels.csv.
