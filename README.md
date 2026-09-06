# CellTracker v0.9.0.6

Call Setup reliability and analysis integration update.

- STOP TEST now finalizes and keeps partial sessions, including a TEST_STOPPED event.
- Call Setup history remembers its scroll position after View Details.
- Call Setup Map uses the canonical OSM tile host/User-Agent path, draws compact colored route dots, and overlays failed-call points in red.
- Automated Call Setup always starts the existing network Recording on both DUTs. The Controller session links its local Recording for continuous route/radio analysis.
- Excel Call Setup report adds a Network Recording worksheet when the linked Recording is available; export also includes the raw network-recording CSV.
- Floating Window setting added: Show MARK / STOP during automated tests. Default OFF to avoid accidental interference; the overlay remains visible.

Build: `./gradlew :app:assembleDebug --stacktrace`
