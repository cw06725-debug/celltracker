# CellTracker v0.9.0.7

Device Link + Call Setup stabilization based on v0.9.0.6 real-device feedback.

## Changes
- Call Setup detail Map is clipped below the tab bar; tab surface is opaque/elevated so MapView cannot visually cover navigation controls.
- Normal CALL_SETUP_SUCCESS remains in Call Setup Attempts/Events but is no longer written into the network Recording as an issue marker. Failure, timeout, link loss and high-latency conditions remain issue markers.
- Navigation scroll positions are preserved for Call Setup history, Ping history, main SIM pages and Settings root navigation so Back returns to the previous position.
- Agent (DUT B) automated network Recording is transferred to Controller (DUT A) after the test over the existing RFCOMM Device Link using chunked Base64 messages.
- Controller stores the received file as `agent_network_recording.csv` in the Call Setup session.
- Export now includes separate `DUT A Network` and `DUT B Network` worksheets when available, plus separate DUT A/B network CSV exports.

Version: 0.9.0.7 (versionCode 50)
