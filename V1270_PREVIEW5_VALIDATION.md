# CellTracker v1.2.70 Preview 5 — Home alignment validation

## Scope

This is a targeted follow-up to Preview 4, not completion of all v1.2.70 features.

- Home / Device Logs: the icon now shows **ADB** rather than **LOG**.
- Home / TranLog and other detected vendor loggers: the card uses the width of a single cell in a 3-column row, aligned with the tests above. The icon has the same 48 dp tile and 40 dp inner artwork standard as installed-app icons.
- Both one-logger and two-logger cases are covered by the `3 - detectedCount` spacer rule. Other Home cards remain unchanged.
- No logger launching/exporting, ADB permissions, or test/report processing code was changed in this patch.
- `versionName=1.2.70`, `versionCode=251`.

## Real-device checks

1. Qualcomm TranLog installed: confirm bottom row shows one *single-column* `TranLog` card, icon visually aligned with TikTok/YouTube; tap opens vendor tool.
2. MTK Logger installed: confirm one single-column MTK Logger card and successful open.
3. Both recognized packages: confirm two equal-width cards and one empty third slot.
4. No supported vendor Logger: confirm no extra empty row appears.
5. Device Logs / ADB Tools: confirm `ADB` icon replaces `LOG`, tap opens existing ADB Tools.
6. Confirm other Home cards and Device Logs original functions remain unchanged.

## Verification limits

- Passed ZIP CRC check and exact source-difference review.
- No local Android SDK/Gradle Android build available. Compile via GitHub Actions and test on real devices.
- Multi-agent sessions, report synchronization, intelligent analysis, and six-sheet Excel remain outside this targeted patch and are not claimed complete.
