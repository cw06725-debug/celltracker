# CellTracker v0.6.1

Hotfix and recording naming update.

## Changes
- Fix compileSdk 34 build failure caused by direct reference to hidden `TelephonyManager.NETWORK_TYPE_LTE_CA`.
- Preserve LTE-CA compatibility detection with local Android network type value 19 plus registered LTE-cell heuristic.
- Add optional task name dialog before starting a recording.
- Task name is included in the recording filename and inherited by CSV/HTML/XLSX/KML exports.
- Recent recordings show the task name for easier identification.
- Remove an accidental duplicate export dialog block.

Filename example:
`CellTracker_Zong_5G_Drive_Test_DualSIM_20260906_115900.csv`
