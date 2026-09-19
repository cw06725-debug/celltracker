# CellTracker V1 Session Engine (.4)

This iteration adds the persistent ScenarioSessionV1 foundation.

Session fields:
- Scenario
- Location
- Operator
- DUT / REF
- Test plan
- Start/end timestamps
- Scenario phase
- Timeline events

Supported phase model:
READY -> BASELINE -> ACTIVE / POWER_OUT -> RECOVERY -> FINISHED

Event types intended for UI wiring:
VIDEO_FREEZE, CALL_DROP, DATA_FAILURE, SLOW_LOADING,
NETWORK_ABNORMAL, POWER_OFF, POWER_RESTORED, CHECKPOINT, CUSTOM.

Persistence uses SharedPreferences JSON for the V1 bootstrap so sessions survive
process restarts. A later migration can move this schema to Room without changing
the scenario/report model.
