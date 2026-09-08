# CellTracker v0.9.3.0.18

WhatsApp image-send timing fallback update.

- Primary T0: WhatsApp `TYPE_VIEW_CLICKED`.
- Fallback T0: first WhatsApp `TYPE_WINDOW_CONTENT_CHANGED` at least 120 ms after ARMED.
- Status shows `CLICK` or `UI CHANGE` T0 source.
- CSV adds `t0_source` for traceability.
- YouTube manual timing logic is unchanged.
