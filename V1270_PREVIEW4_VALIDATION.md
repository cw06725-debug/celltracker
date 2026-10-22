# CellTracker v1.2.70 Preview 4 — Call Setup responsiveness / TranLog icon

**Baseline:** v1.2.70 Preview 3. `versionName = 1.2.70`, `versionCode = 250`.

## Addressed in source

- **Call Setup initial overlay:** No eager Bluetooth refresh, permission request, SIM subscription enumeration, or telephony phone-number lookup while the Home-hosted `setupOnly` dialog is displayed. These operations remain available when entering the existing **Configure Device Link / SIM** screen. SIM discovery and phone-number lookups on that screen are moved to `Dispatchers.IO` to reduce main-thread blocking. Existing saved per-SIM phone numbers are still read immediately to decide whether Start is enabled. When a number is not yet saved, users must enter the configuration screen; no fake connected state is shown.
- **Call Setup layout:** Standard shared Test Setup dialog, scrollable body, fixed footer with Cancel, Start Test and conditional Configure Device Link / SIM. Advanced settings are collapsed by default to avoid the previous overlong content. Direction, task name, count, timeout and A/B SIM selection remain in the primary section. Hold duration, interval, high latency threshold, network recording notice, voice monitor, and manual mode remain available under Advanced options. The test engine, configuration fields and report schema are unchanged.
- **Dialog scrolling:** Reset scroll position at initial title presentation, keeping actions visible and the body scrollable.
- **Home vendor icon:** The *TranLogManager* card is displayed as **TranLog**, with native package/application launch unchanged. Its original vendor artwork renders at **30 dp inside the existing 48 dp icon tile**; other application cards retain their existing 40 dp artwork and card sizes. Version metadata remains unchanged.
- **Home recomposition:** Home tile app-version lookups use `remember` to avoid repeated blocking PackageManager requests when the overlay opens.

## Modified files

- `app/src/main/java/com/example/celltracker/CallSetupScreen.kt`
- `app/src/main/java/com/example/celltracker/UnifiedTestSetup.kt`
- `app/src/main/java/com/example/celltracker/MainActivity.kt`
- `app/build.gradle.kts`
- `CHANGELOG.md`
- `V1270_PREVIEW4_VALIDATION.md` (new)

## Checks completed

1. Manually compared the changed sections against Preview 3; no changes to core Call Setup service, data/reports, TranLogManager package component or Device Link protocol.
2. Ran a standalone Kotlin parser/compiler pass on the touched Kotlin files. No new `expecting`/parser errors appeared. Most unresolved symbols are expected because **Android SDK, Compose, and project classpath are not installed in this environment**. This is not a substitute for Android Gradle compilation.
3. ZIP file CRC verification should be performed after packaging.

## GitHub Actions / real-device acceptance

1. Compile the Android app in GitHub Actions and inspect `:app:compileDebugKotlin` output.
2. Tap **Call** several times from Home with no DUT B connected. Dialog should open quickly, show Call Setup with its fields starting at top and fixed footer. Cancel returns Home; **Configure Device Link / SIM** opens the original connection screen.
3. With paired DUT B and valid A/B phone numbers, verify **Start Test** activates; toggle Advanced options, change each config, and confirm correct call setup run / report retention.
4. Increase Android font size/display scaling and rotate if supported; body should scroll without hiding Cancel, Start Test or Configure Device Link / SIM. Confirm keyboard does not permanently hide actions.
5. Confirm no automatic permissions dialog interrupts opening the initial Call Setup sheet; required permissions remain requestable on the configuration screen.
6. On Transsion Qualcomm, check **TranLog** card is smaller and aligned with other Home tiles, and clicking it still opens `com.transsion.TranLogManager/.TranLogManageActivity`.
7. Regression-check YouTube, Ping, WhatsApp, Throughput initial popups, Reports app icons, MTK Logger, Device Logs, and existing data.

## Still out of scope for this targeted preview

- Multi-Agent (1 Controller + 3 Agents) synchronization and multi-sheet DUT/REF analysis.
- Direct third-party access to `/data/debuglogger/` without privileged permissions.
- Real-device runtime performance cannot be verified here; if the dialog still stutters, capture a performance trace around the click and verify whether the Home recomposition or the Device Link service is blocking the main thread.
