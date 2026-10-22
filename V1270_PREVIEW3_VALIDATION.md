# CellTracker v1.2.70 Preview 3 — Home test setup + real application icon fix

## Status / baseline

- Based on user's **v1.2.70 Preview 2** project, not an earlier v1.2.69 archive.
- `versionName=1.2.70`, `versionCode=249` (Preview 2: 248).
- Source changes only. **Not Android Gradle compiled** in this environment: Android SDK and androidx Compose dependencies are missing. The available standalone Kotlin parser did not report new parser syntax errors; missing Android imports are expected and do not prove type correctness.
- Preserve original test engines, logs, repositories, reporting files, Device Link, existing TranLogManager entry, and user data. No changes to data formats / schema.

## What changed

1. **Home behind the Test Setup dialog.** The initial configuration of YouTube Loading, WhatsApp Image Send, Ping, Call Setup, and HTTP Throughput is now displayed over the *same composed Home interface* used by TikTok Upload. Previously the destination's full test/history page was composed under the dialog. **Now RootDestination remains Main during initial setup**. The existing test components are drawn in a stable sibling layer on top of Home and do not compose the old test page until the user advances. On Start/Prepare the route switches to that test destination, while the same test component instance retains its config and launcher state. `Cancel` closes the destination and shows Home again. This reuses the original input fields and test actions.
2. **Navigating into business execution.** The test page only appears after starting Ping/Call/Throughput or selecting Prepare for YouTube/WhatsApp, which continues into its existing Test Metadata and Accessibility/MediaProjection paths. Call's Configure Device Link / SIM action exits preparation mode to show the existing connection screen. Agent mode still shows the link screen rather than controller-only setup.
3. **Reports app logos.** Reports by Type cards for TikTok Upload, TikTok Video Lag, YouTube Loading and WhatsApp Image Send now load the device's installed application icon, same component/packages as Home. If absent/unqueryable, the current fallback icon remains; no fake brand icon or copyrighted asset bundled.
4. **Home card visual consistency.** All Home test cards use 124 dp height and 48 dp rounded icon containers. Native app artwork is centered in a 40 dp square; drawn Ping/Call/Recording symbols are centered on a 44 dp canvas. Metadata and installed-app detection remain unchanged.
5. **Version / release log.** Incremented preview build versionCode to 249 and added CHANGELOG entry.

## Modified files

- `app/src/main/java/com/example/celltracker/MainActivity.kt`
- `app/src/main/java/com/example/celltracker/CallSetupScreen.kt`
- `app/src/main/java/com/example/celltracker/ThroughputTest.kt`
- `app/build.gradle.kts`
- `CHANGELOG.md`
- This validation file

## Tests to run after GitHub Actions build

**Initial dialogs (critical):**
1. From Home, tap YouTube. The dialog should overlay **CellTracker Home (header, SIM card, test cards, bottom navigation)** rather than `YouTube Video Loading` legacy header and history. Tap Cancel: Home. Open again, adjust options, tap Prepare and complete metadata. Confirm original YouTube test launches and reports save.
2. Repeat for WhatsApp Image Send. Confirm Accessibility workflow, metadata and overlay work.
3. Repeat for Ping. Confirm START TEST enters live test UI, records RTT and writes history. Tap Cancel before Start: Home.
4. Repeat for Throughput. Confirm START TEST runs real HTTP download and the CSV is shareable. Cancel before Start: Home.
5. Tap Call. Verify Home backdrop; if not paired, tap CONFIGURE DEVICE LINK / SIM and ensure connection page opens instead of a blank Home. When connected, run call test with existing SIM validation. Also test Agent mode.
6. TikTok Upload and TikTok Video Lag: make sure their existing Home dialog remains unchanged.

**Icons (critical):**
7. Home: all test cards equal height within a row; TikTok, YouTube, WhatsApp logos and Logger package artwork visually balanced with drawn Ping/Call/Recording/Throughput icons.
8. Reports: TikTok and YouTube and WhatsApp category cards show installed **application icons** (not ♪/▶/phone glyphs). Confirm report counts, open, export and delete still work. On an app not installed, verify fallback rather than crash.

**Regression:**
9. Qualcomm TranLogManager, MTK Logger detection, Log D getprop/setprop/restore and existing `/data/debuglogger/` ADB export; user-selected log file import/share.
10. Home/Reports/Settings bottom navigation, local data/saved reports, screen recording and Bluetooth Device Link.

## Not completed in this preview

- 1 Controller + 3 concurrent Agent multi-device orchestration and report synchronization.
- DUT/REF summary, analysis rules, and 6-sheet Excel export.
- Android no-ADB direct access to protected `/data/debuglogger/` (not generally permitted by sandbox).
- HTTP upload throughput and standalone WhatsApp Call workflow.

This is a **targeted UI fix preview**, not the entire requested v1.2.70 roadmap.

## Git commands

```bash
git status
git add .
git commit -m "fix: preview3 home test setup and real app icons"
git push
```
