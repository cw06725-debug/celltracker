# CellTracker v0.4.2

Fix and UX iteration based on v0.4.1 field testing.

## Changes
- Live-location pin is now inspectable even when there is no active/recorded track sample. The detail card is built from the current serving-cell snapshot + current GPS fix.
- Live Map no longer replays the latest historical recording after Stop. Historical tracks stay in Recording Detail; Live Map shows only the active recording plus current live location.
- Added fade + subtle scale transitions between main/settings/recording detail and between main/live map.
- Settings reorganized into second-level pages: Sampling, Marker Button, Map Point Details, Issue Types. This leaves room for future third-level configuration without making the root page excessively long.
- Version: 0.4.2 (versionCode 12).
