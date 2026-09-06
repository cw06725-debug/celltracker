# CellTracker v0.9.0.10.1

Hotfix for Settings root scroll behavior. Every new entry from the main screen creates a fresh Settings visit and starts the Settings root at scroll position 0, while Back still restores the main screen position. Dark mode and the existing v0.9.0.10 behavior are unchanged.

# CellTracker v0.9.0.10

Changes:
- Settings root always starts from top on each fresh visit.
- Parent/home scroll position is synchronously persisted on navigation disposal, so Back restores the exact prior position more reliably.
- Call Setup local phone number fields use external labels to avoid transient label/value overlap on screen entry.
- Added system-following dark mode for Compose UI plus a night Android window theme.
- Long Call behavior from v0.9.0.9.1 is unchanged for tomorrow's field validation.

Build note: local assembleDebug could not run because this environment cannot resolve services.gradle.org for Gradle 8.7. Use GitHub Actions for full build verification.
