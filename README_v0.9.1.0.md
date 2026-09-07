# CellTracker v0.9.1.0

Voice Monitor Phase 1:
- Call Setup: every established call performs A→B and B→A acoustic voice checks.
- Long Call: same check at connection, then every ~30 s while enough hold time remains.
- Uses 1000 Hz / 1400 Hz short tones, speakerphone routing and AudioRecord + Goertzel analysis.
- Results: VOICE_OK, NO_AUDIO, HIGH_NOISE, VOICE_CHECK_FAILED.
- RECORD_AUDIO and MODIFY_AUDIO_SETTINGS added; microphone runtime permission requested from Call Setup page.
- Voice results stored in voice_quality.csv and exported to Excel `Voice Quality` sheet + HTML report + standalone CSV.
- Voice anomalies create VOICE_QUALITY_ISSUE events for correlation with network/GPS data.

Important: this is an acoustic best-effort monitor, not POLQA/PESQ/MOS. OEM audio routing and physical DUT placement can affect thresholds. Field calibration is expected after first real-device tests.
