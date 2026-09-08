# CellTracker v0.9.3.0.15

- Added independent WhatsApp Image Send manual timing test.
- Workflow: START -> ARMED -> tap WhatsApp Send = T0 -> tap SENT = T1 -> delay = T1 - T0.
- Uses a dedicated Accessibility service for WhatsApp / WhatsApp Business and does not change the YouTube v0.9.3.0.14 manual timing flow.
- T0 is the user's real ACTION_DOWN time; the captured tap is replayed into WhatsApp.
- Each completed sample stores wall-clock T0/T1, monotonic timestamps, delay and network snapshot in a separate WhatsApp CSV history.
