# CellTracker v0.9.3.0.16

## WhatsApp image-send timing fix

- Removed the full-screen touch-capture overlay from WhatsApp timing mode.
- START -> ARMED now listens for WhatsApp `TYPE_VIEW_CLICKED` events instead of intercepting/replaying the user's touch.
- A recognized WhatsApp Send button click is recorded as T0 while the original user click reaches WhatsApp normally.
- SENT remains the manual T1 marker and delay is calculated as T1 - T0.
- Send-button matching is limited to WhatsApp/WhatsApp Business package events and the clicked node / immediate parent chain to reduce false positives.
- YouTube manual timing logic is unchanged from v0.9.3.0.14 behavior.
