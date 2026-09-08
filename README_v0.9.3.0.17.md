# CellTracker v0.9.3.0.17

## WhatsApp manual T0 fix

- Keeps WhatsApp touch delivery fully native; no full-screen touch capture overlay.
- After START -> ARMED, the first `TYPE_VIEW_CLICKED` event from `com.whatsapp` or `com.whatsapp.w4b` is recorded as T0.
- Removed Send-button node/text matching, which failed on localized WhatsApp UIs and on versions whose media-send button exposes different accessibility metadata.
- CellTracker overlay button clicks cannot become T0 because they come from CellTracker's own package, not WhatsApp.
- SENT remains the only T1 marker; delay = T1 - T0.
- YouTube timing behavior is unchanged.
