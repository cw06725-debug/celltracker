# CellTracker v0.9.0.10.2

Hotfix for the Settings fresh-entry scroll reset compile error introduced in v0.9.0.10.1.

Changes:
- Replaced direct `ScrollState` construction with `rememberScrollState(initial = 0)`.
- Reset Settings root to top with `LaunchedEffect(visitId) { rootScrollState.scrollTo(0) }` only for a new Main -> Settings visit.
- Settings child page -> Back keeps the current Settings root position for the same visit.
- Main page scroll restoration remains unchanged.
- No changes to dark mode, Device Link, Call Setup, dual-DUT network report sync, or Long Call logic.

Version: 0.9.0.10.2 (versionCode 56)
