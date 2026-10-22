# CellTracker v1.3.0 preview — validation and limitations

Basis: v1.2.70 Preview 5 (versionCode 251). VersionCode 252 in this source package.

## Code changes
- LTE RSSNR: evaluate subscription-scoped SignalStrength against serving LTE RSRP (within 3 dB tolerance). A matching RSRP makes the source more plausible, but does not prove identical PCI. LTE zero can be valid; missing remains --. New Signal card field SINR Source indicates selected public-API origin.
- Cell Info: remove icons from all section headers while keeping card layout and fields.
- WhatsApp and YouTube setup: replace stacked giant action buttons with compact Accessibility status/settings card and fixed footer Cancel / Prepare Test.
- Common rounded blue floating overlay styling across recording, YouTube, WhatsApp, TikTok, Basement and Visual AI Collector; preserve interactive buttons, dragging and transparency settings where previously supported.
- VersionName v1.3.0, versionCode 252.

## Must test on devices
1. Build Gradle debug via GitHub Actions; this environment does not have Android SDK.
2. On Jazz SIM2 NSA (LTE anchor), compare LTE Band/RSRP/RSSNR from CellInfo and SignalStrength, and compare with Cellular-Z. The screenshots show CellTracker 0 dB vs Cellular-Z 9.0 dB; this is not yet a verified root cause.
3. Test true LTE RSSNR=0 dB remains 0. Missing values remain --; verify no cross-SIM contamination.
4. Verify YouTube/WhatsApp setup opens on Home and buttons remain visible, with accessibility grant status and actual start/prepare flows.
5. Verify all overlay appearances and drag/controls on TikTok Upload/Lag, YouTube, WhatsApp, Basement, Network Recording, Visual AI; do not obstruct target app or accidentally trigger touches.
6. Verify existing Reports, Device Link, TranLog and ADB tools still work.

## Not delivered in this UI/SINR preview
Multi-agent Controller+3 Agents transport/report aggregation, DUT/REF analysis, six-sheet Excel report; no claim of implementation. No APK build or functional validation is claimed. A local Kotlin parse check (without Android dependencies) did not surface new syntax errors; Android Gradle compile and device behavior remain unverified.
