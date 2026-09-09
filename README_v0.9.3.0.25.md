# CellTracker v0.9.3.0.25

- Fixes the Kotlin compile error introduced in v0.9.3.0.24 (illegal regex escape in YouTube title filtering).
- Exported YouTube and WhatsApp reports are grouped by test date and test type.
- YouTube: Downloads/CellTracker/yyyy-MM-dd/YouTube/
- WhatsApp: Downloads/CellTracker/yyyy-MM-dd/WhatsApp/
- HTML summary, Excel report and CSV for one test are saved into the same folder.
- Uses the test session date (startedAt), not the export date.
