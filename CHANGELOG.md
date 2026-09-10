# CellTracker Changelog

## v0.9.3.0.42

- Unified all five root tabs under one navigation state; Map no longer has a separate navigation boolean.
- Fixed swipe getting stuck around the first three tabs.
- A single drag can now travel across all five tabs and snaps to the nearest destination.
- Bottom glass bar now overlays page content instead of reserving a solid Scaffold bottom region.
- Strengthened the liquid-glass look with layered translucency, specular highlights, caustic/radial depth and a moving selection lens.
- Keeps inline Settings, origin-tab return behavior and grouped Reports.

## v0.9.3.0.41

- Rebuilt the bottom navigation touch model so the whole glass capsule owns drag/tap input; child items no longer consume the swipe.
- Swipe left/right now changes one top-level tab reliably.
- The selected glass lens follows the finger while dragging and snaps to the next/previous item.
- Strengthened iOS-26-inspired glass appearance: milky translucency, specular highlight, layered radial depth, movable glass lens, soft outer shadow, and translucent border.
- Removed the flat button-like selected treatment.
- Kept Settings inline, origin-tab return behavior, and grouped Reports from v0.9.3.0.40.
- Project remains cleaned to README.md + CHANGELOG.md only.

## v0.9.3.0.40

- Reworked bottom navigation into a cleaner liquid-glass-inspired floating capsule.
- Removed the visual separator line above the bottom function area.
- Bottom bar supports left/right swipe to switch top-level sections.
- Settings is now an inline top-level tab; it no longer opens as a separate root destination.
- Returning from a secondary test/detail page preserves the originating top-level tab.
- Settings child pages return to Settings instead of Cell Info.
- Reports is now an inline report browser grouped by test type; it no longer jumps to test setup screens.
- Merged historical README_v*.md files into CHANGELOG.md.

## v0.9.3.0.39

# CellTracker v0.9.3.0.39

Basement report refinement + main UI navigation redesign.

## Basement report
- Fixed Points: replaces Packet Loss with Ping Success Rate.
- Per-route network analysis merges RAT distribution + RAT transitions into a single RAT/Band timeline.
- Timeline rows include RAT, start time, end time, duration, share, and Band.
- Band Distribution keeps total duration/share.
- Other Network Events now show start, end, duration, current Band, From and To.
- summary.csv uses Success Rate for fixed-point Ping.

## Selected SIM
- Basement setup now displays `SIM <slot> · <operator>` e.g. `SIM 1 · Ufone`.
- Removes duplicated operator/label output such as `Ufone · Ufone · LTE`.

## Main UI
Adds a floating liquid-glass-inspired bottom navigation:
- 测试
- Cell Info
- Map
- Setting
- Reports

Visual treatment uses translucent rounded surfaces, subtle borders, elevation and selected-state glow.
- Tests are moved out of Cell Info into the Test tab.
- Recording/report history is moved out of Cell Info.
- Reports tab is the central entry for Ping / Basement / YouTube / WhatsApp / Call Setup reports plus Network Recording reports.

## v0.9.3.0.38

# CellTracker v0.9.3.0.38 — Basement Weak Coverage refinement

Based on v0.9.3.0.37 field-test feedback.

## Floating controller
- Test setup now uses PREPARE FLOATING TEST.
- Preparing does not create T0 or start network logging.
- Floating controller shows START TEST; pressing it creates the session T0 and starts 1 Hz logging.
- Floating window can be dragged by its title area.
- Main action remains a single large button.

## Recovery
- UI label renamed to `Recovery timeout s`.
- This value is only the maximum wait after ARRIVE START.
- LTE/5G Recovery:
  - if already available at ARRIVE START => 0.0 s;
  - otherwise first restored 1 Hz sample timestamp minus ARRIVE START, confirmed by the next consecutive sample.
- Data Recovery:
  - first successful real recovery Ping completion time minus ARRIVE START.
- This fixes the misleading ~1.2 s LTE recovery shown in v0.37 when LTE had never disappeared.

## RAT distribution
- Segment distribution now allocates the entire segment from exact segment start to exact segment end.
- Fixes all-4G segments showing 96–99% instead of 100%.
- HTML now shows 5G / 4G / 3G / 2G / No Service / Other.

## Network Events report
Each Route Segment now has its own analysis block:
- RAT distribution and duration/share
- Band distribution and duration/share
- RAT transition count + From/To + segment elapsed time + timestamp
- Band transition count + From/To + segment elapsed time + timestamp
- Other events (cell/NR/data/no-service)

## Report UX
- After Finish: PREVIEW opens summary.html.
- SHARE / EXPORT shares all report files from the session folder.
- Existing automatic export to Downloads/CellTracker/Reports/... remains.

## v0.9.3.0.37

# CellTracker v0.9.3.0.37

Build fix for Basement Weak Coverage Test.

- Fix BasementTestService coroutine `isActive` compile error.
- Restore missing `android.content.ContentValues` import in ScreenCaptureService.

## v0.9.3.0.36

# CellTracker v0.9.3.0.36 — Basement Weak Coverage Test MVP

New independent test mode: **Basement Weak Coverage**.

## Workflow
START → B1 → B2 → B1 Return → START → Recovery → Finish

## MVP functions
- Independent Basement test configuration screen.
- Dedicated foreground service + wake lock.
- Dedicated floating controller with one main action button.
- 1 Hz cellular sampling for the selected SIM.
- LTE / NR serving information plus 2G/3G fallback parsing (GSM/WCDMA).
- Automatic events:
  - RAT change
  - Cell change
  - Band change
  - NR Add / Release
  - Data-state change
  - No Service start / end
- Four route segments:
  - START → B1
  - B1 → B2
  - B2 → B1 Return
  - B1 → START Return
- B1 First / B2 / B1 Return:
  - fixed stabilization wait (default 30 s)
  - automatic Ping (default 60 packets / ~60 s)
  - live progress
  - loss / avg / min / max RTT
- START return recovery:
  - LTE recovery
  - real data recovery using Ping
  - 5G/NR recovery
  - 2 consecutive confirmations for stability
  - default max wait 60 s
  - 5G recovery is N/A if NR was not present at the starting point
- Automatic statistics:
  - RAT distribution per segment
  - initial 5G retention
  - first contiguous LTE/4G retention
  - No Service count / total / longest duration
  - LTE/NR signal statistics
  - network transition events
- Reports automatically exported after Finish:
  `Download/CellTracker/Reports/YYYY-MM-DD/Basement/<Operator>_<Device>_<timestamp>/`
  - `summary.html`
  - `summary.csv`
  - `network_raw.csv`
  - `events.csv`
  - `ping.csv`

## Test preparation
- Turn Wi-Fi OFF.
- Make the selected CellTracker SIM the Android default mobile-data SIM.
- Grant Display over other apps for the floating controller.
- Location + Phone permissions are required for Android CellInfo.

## First-version limitations
- RAT/event timestamps retain millisecond timestamps but detection resolution is sampling-bound (~1 s).
- No GPS/map/automatic B1/B2 recognition.
- No adaptive network-stability algorithm yet; fixed wait is used.
- Ping PASS means at least one reply; packet loss is reported separately.

## v0.9.3.0.35

# CellTracker v0.9.3.0.35

Fixes Visual AI data visibility/export.

Why v0.34 looked empty in Downloads:
- Collection succeeded (frame count increased), but frames were intentionally stored in app-private external storage:
  `/storage/emulated/0/Android/data/com.example.celltracker/files/VisualAI/...`
- Modern Android file managers often hide/restrict Android/data.
- Therefore `Downloads/CellTracker` stayed empty.

v0.35 behavior:
- Keep collecting into app-private storage for performance.
- On STOP COLLECTION, automatically ZIP the entire session.
- Export the ZIP via MediaStore to:
  `Downloads/CellTracker/VisualAI/<date>/CellTracker_VisualAI_<date>_<time>.zip`
- UI shows EXPORTING / EXPORTED / EXPORT_FAILED and the public ZIP path.
- ZIP contains all PLAYER/RECS JPEGs plus labels.csv.

## v0.9.3.0.34

# CellTracker v0.9.3.0.34

Visual AI Collector reliability + floating labels.

## Fixes
- Replaces ImageReader listener collection with a robust IO polling loop (~2 fps).
- Fixes the `CAPTURING` but `Frames: 0` failure seen on device.
- Adds a top floating collector bar over YouTube:
  - live frame count / attempt / phase
  - T0
  - PLAY
  - RECS
  - STOP
- START COLLECTION now checks Android "Display over other apps" permission first.
- Floating bar is constrained to the top ~52dp; PLAYER ROI begins at 7% screen height so the collector controls are not part of the player training crop.
- Keeps labels.csv and T0-relative metadata in image names.

## Test
1. Open Visual AI Collector.
2. START COLLECTION.
3. Grant Display over other apps if requested, return and START again.
4. Grant screen sharing.
5. YouTube opens and the top collector bar should appear.
6. Verify F count rises.
7. For each sample: T0 -> tap video -> PLAY -> RECS.

## v0.9.3.0.33

# CellTracker v0.9.3.0.33

Build-fix release for the independent Visual AI Collector.

- Fixes Kotlin compile errors at MainActivity.kt TopAppBar usages.
- Restores `@OptIn(ExperimentalMaterial3Api::class)` on `VideoLoadingScreen`.
- Keeps Visual AI Collector with MediaProjection.
- Keeps manual Ground Truth buttons: T0 / PLAY OK / RECS OK.
- Keeps labels.csv and attempt/phase metadata in frame names.

## v0.9.3.0.32

# v0.9.3.0.32
- Fix compile error caused by duplicated @OptIn annotation in v0.9.3.0.31.
- Keep independent MediaProjection Visual AI Collector.
- Add manual ground-truth labels: T0, PLAY OK, RECS OK.
- labels.csv records each label timestamp and offset from T0.
- Saved frame filenames include attempt, phase, frame number and T0-relative time.

## v0.9.3.0.31

# CellTracker v0.9.3.0.31 — Independent Visual AI Collector

- Adds a dedicated Visual AI Collector entry inside YouTube Video Loading.
- Collector no longer depends on the old START/PLAY/RECS/LOADED attempt logic.
- START COLLECTION requests Android screen-sharing permission and opens YouTube.
- MediaProjection + ImageReader continuously samples the real screen at ~2 fps.
- Each frame saves PLAYER and RECS ROI images.
- Collector screen visibly shows CAPTURING, frame counts, save failures, and the exact saved path.
- STOP COLLECTION ends frame collection.
- During collection, operate YouTube naturally; CellTracker START/LOADED is not needed.

## v0.9.3.0.30

# CellTracker v0.9.3.0.30 — Visual AI Collector

## Why this build
v0.9.3.0.29 can work initially and then stop auto-detecting on some devices. One likely contributor is AccessibilityService screenshot throttling: the previous loop requested screenshots about every 180 ms, which is too aggressive for Android/Samsung implementations and can lead to screenshot interval errors.

## Changes
- Screenshot detector cadence changed from ~180 ms to ~550 ms.
- Keeps PLAY + RECS dual-success criterion.
- Adds Visual AI Collector.
- After each YouTube T0, each successful screenshot saves:
  - PLAYER ROI
  - RECS ROI
- Collector cadence is >=500 ms.
- JPEG work runs on the screenshot executor, not the UI thread.
- Saved under:
  Downloads/CellTracker/VisualAI/<date>/session_HHmmss/attempt_XXX/
- File name example:
  +01234ms_PLAY1_RECS0_PLAYER.jpg
  +01234ms_PLAY1_RECS0_RECS.jpg
- PLAY/RECS in the filename record what the current v0.30 heuristic believed at that moment.
- Manual LOADED remains available.

## What to collect
Run about 10–20 real YouTube loading attempts across good/poor network conditions. Include cases where:
- player starts before recommendations;
- recommendations appear before playback;
- spinner persists;
- page loads normally;
- auto detector misses or times out.

Then zip the corresponding VisualAI session folder and provide it for model/threshold design.

## v0.9.3.0.29

# CellTracker v0.9.3.0.29 — YouTube success = Playback + Recommendations

User-defined success criterion:
1. The selected video is playing normally.
2. The recommendation list under the video has loaded normally.

T1 is recorded only when BOTH conditions are true.

## Detection
- PLAY ready: AUTO_VISUAL or AUTO_AUDIO.
- RECS ready: bounded Accessibility scan of the lower part of the YouTube page.
  It requires multiple visible text labels, longer title-like labels and clickable cards.
- Two consecutive RECS confirmations are required.
- Final report detection is AUTO_VISUAL+RECS or AUTO_AUDIO+RECS.
- Floating status shows PLAY✓/PLAY… and RECS✓/RECS… independently.
- Manual LOADED remains a fallback.
- AUTO timeout increased to 15 s.

This prevents a video starting early from being counted as successful while the rest of the watch page is still blank/loading.

## v0.9.3.0.28

# CellTracker v0.9.3.0.28

## Fix
v0.9.3.0.27 called AccessibilityService.takeScreenshot but the accessibility-service metadata did not declare android:canTakeScreenshot="true". Android therefore refused screenshot capture and AUTO visual T1 could never work.

## Changes
- Added android:canTakeScreenshot="true" to the unified accessibility service.
- Added screenshot failure diagnostics in the floating status text.
- Added a secondary AUTO_AUDIO detector using AudioManager.isMusicActive.
- AUTO_AUDIO requires a real inactive -> active transition after T0 and two consecutive confirmations.
- Relaxed visual thresholds slightly now that actual screenshots are available.
- Manual LOADED remains the final fallback.

## Detection values written to reports
- AUTO_VISUAL
- AUTO_AUDIO
- MANUAL_BUTTON

## v0.9.3.0.27

# CellTracker v0.9.3.0.27 — YouTube Auto Detection PoC

## Goal
Reduce operator timing error on YouTube loading tests.

## Manual start + automatic finish
1. START
2. ARMED
3. Tap a YouTube video -> T0
4. CellTracker samples the visible player region about every 150 ms
5. Three consecutive broad-frame motion detections -> automatic T1
6. Delay is saved automatically and the normal return flow continues

## Safety / fallback
- LOADED remains available as a manual T1 fallback.
- AUTO waits at least 500 ms from T0 before completion.
- A single page transition cannot trigger T1; broad motion must repeat across 3 frames.
- Loading-spinner motion should affect too small an area to pass the threshold.
- AUTO gives up after 12 seconds and asks the tester to use LOADED.

## Implementation
- Uses AccessibilityService.takeScreenshot (Android 11+) — no MediaProjection permission is required.
- Only 24 x 14 luminance samples are analyzed per frame.
- Screenshot work runs on a dedicated executor.
- Existing reports use Detection=AUTO_VISUAL or MANUAL_BUTTON.

## v0.9.3.0.26

# CellTracker v0.9.3.0.26

- Restores YouTube manual T0 detection on builds that do not emit TYPE_VIEW_CLICKED.
- T0 sources: ACCESSIBILITY_CLICK, WINDOW_CHANGE fallback, UI_CHANGE fallback.
- Fallback does not walk the full Accessibility tree.
- Fixes 1970-01-01 report folders by preserving the real test start time.
- Old YouTube records with started=0 now fall back to first sample/file time.
- Report folders remain Downloads/CellTracker/YYYY-MM-DD/YouTube and /WhatsApp.

## v0.9.3.0.25

# CellTracker v0.9.3.0.25

- Fixes the Kotlin compile error introduced in v0.9.3.0.24 (illegal regex escape in YouTube title filtering).
- Exported YouTube and WhatsApp reports are grouped by test date and test type.
- YouTube: Downloads/CellTracker/yyyy-MM-dd/YouTube/
- WhatsApp: Downloads/CellTracker/yyyy-MM-dd/WhatsApp/
- HTML summary, Excel report and CSV for one test are saved into the same folder.
- Uses the test session date (startedAt), not the export date.

## v0.9.3.0.24

# CellTracker v0.9.3.0.24

- Unified YouTube + WhatsApp into one Android AccessibilityService / one settings switch.
- YouTube manual START no longer walks the whole accessibility tree.
- After YouTube T0, accessibility events are ignored until LOADED/T1.
- LOADED return uses one Back plus a fixed settle delay; repeated page-tree scans were removed.
- Video title is recovered from the clicked node and its immediate parent/siblings only.
- WhatsApp timing logic/report behavior is preserved inside the unified service.

## v0.9.3.0.23

# CellTracker v0.9.3.0.23

Performance-only optimization for floating timing controls.

- YouTube and WhatsApp clocks refresh every 50 ms instead of 20 ms to reduce UI-thread pressure.
- First START creates the report file on Dispatchers.IO instead of the Accessibility/UI thread.
- LOADED/SENT lock T1 immediately before any snapshot or file work.
- Network snapshot and CSV append run off the UI thread.
- STOP metadata finalization also runs in the background.
- Timing state machine, T0/T1 rules, reports and overlay layout are otherwise unchanged.

## v0.9.3.0.22

# CellTracker v0.9.3.0.22
WhatsApp overlay visual trial:
- START/ARMED, SENT and STOP are on one row.
- STOP is a compact 92dp button.
- First STOP tap changes to SURE?; tap again within 4 seconds to stop.
- YouTube overlay is unchanged in this trial.

## v0.9.3.0.21

# CellTracker v0.9.3.0.21

## WhatsApp overlay
- STOP is smaller and aligned to the right.
- STOP requires a second tap within 4 seconds to confirm, reducing accidental termination.

## YouTube manual timing
- Semi-auto now uses the same interaction model as WhatsApp.
- START -> ARMED -> native YouTube tap records T0 -> LOADED records T1.
- The full-screen touch-capture overlay is no longer installed in manual YouTube mode.
- Preferred T0 source is TYPE_VIEW_CLICKED; list-to-playback UI transition is used only as fallback.
- STOP is smaller and requires a second confirmation tap.
- Existing report/history/export and automatic return behavior are retained.

## v0.9.3.0.20

# CellTracker v0.9.3.0.20

WhatsApp Image Send reporting is now aligned with the YouTube report UI.

- Detail report uses 3 tabs: Summary / Attempts / Map
- Full-screen report detail
- Bottom actions: Delete / Close / Share
- Export success dialog uses the shared CellTracker export dialog
- Export dialog actions: Open Summary / Open Excel / Share / Close
- WhatsApp map displays per-attempt GPS markers when location is available
- Existing WhatsApp T0/T1 timing logic and YouTube logic are unchanged

## v0.9.3.0.19

# CellTracker v0.9.3.0.19

## WhatsApp Image Send report
Adds a YouTube-style report workflow for WhatsApp image-send timing.

- History cards: View Details / Export & Share / Delete
- Detail report: Summary + per-sample attempts
- Statistics: Samples, Average, Median, P90, P95, Min, Max
- Per sample: T0, T1, delay, T0 source, RAT, RSRP, RSRQ, SINR, RSSI, Band, PCI, ARFCN, operator and SIM
- Export: HTML summary + XLSX report + raw CSV
- Files are saved to Downloads/CellTracker
- Existing YouTube timing behavior is unchanged.

## v0.9.3.0.18

# CellTracker v0.9.3.0.18

WhatsApp image-send timing fallback update.

- Primary T0: WhatsApp `TYPE_VIEW_CLICKED`.
- Fallback T0: first WhatsApp `TYPE_WINDOW_CONTENT_CHANGED` at least 120 ms after ARMED.
- Status shows `CLICK` or `UI CHANGE` T0 source.
- CSV adds `t0_source` for traceability.
- YouTube manual timing logic is unchanged.

## v0.9.3.0.17

# CellTracker v0.9.3.0.17

## WhatsApp manual T0 fix

- Keeps WhatsApp touch delivery fully native; no full-screen touch capture overlay.
- After START -> ARMED, the first `TYPE_VIEW_CLICKED` event from `com.whatsapp` or `com.whatsapp.w4b` is recorded as T0.
- Removed Send-button node/text matching, which failed on localized WhatsApp UIs and on versions whose media-send button exposes different accessibility metadata.
- CellTracker overlay button clicks cannot become T0 because they come from CellTracker's own package, not WhatsApp.
- SENT remains the only T1 marker; delay = T1 - T0.
- YouTube timing behavior is unchanged.

## v0.9.3.0.16

# CellTracker v0.9.3.0.16

## WhatsApp image-send timing fix

- Removed the full-screen touch-capture overlay from WhatsApp timing mode.
- START -> ARMED now listens for WhatsApp `TYPE_VIEW_CLICKED` events instead of intercepting/replaying the user's touch.
- A recognized WhatsApp Send button click is recorded as T0 while the original user click reaches WhatsApp normally.
- SENT remains the manual T1 marker and delay is calculated as T1 - T0.
- Send-button matching is limited to WhatsApp/WhatsApp Business package events and the clicked node / immediate parent chain to reduce false positives.
- YouTube manual timing logic is unchanged from v0.9.3.0.14 behavior.

## v0.9.3.0.15

# CellTracker v0.9.3.0.15

- Added independent WhatsApp Image Send manual timing test.
- Workflow: START -> ARMED -> tap WhatsApp Send = T0 -> tap SENT = T1 -> delay = T1 - T0.
- Uses a dedicated Accessibility service for WhatsApp / WhatsApp Business and does not change the YouTube v0.9.3.0.14 manual timing flow.
- T0 is the user's real ACTION_DOWN time; the captured tap is replayed into WhatsApp.
- Each completed sample stores wall-clock T0/T1, monotonic timestamps, delay and network snapshot in a separate WhatsApp CSV history.

## v0.9.3.0.14

# CellTracker v0.9.3.0.14

VersionCode: 90

## Semi-auto manual START fix

- Manual START is authoritative: after ARMED, the next tap-like gesture inside the YouTube media/content area is recorded as T0.
- Removed mini-player rejection from the armed tap path. The old detector could walk a large parent container and see mini-player labels from another child, falsely classifying an ordinary video card as the mini-player.
- Reduced START -> ARMED settle time from 320 ms to 120 ms so repeated samples respond faster.
- Scroll, long-press, multi-touch, and navigation-area taps still do not create T0.
- LOADED remains the only manual T2 endpoint.

## v0.9.3.0.13

# CellTracker v0.9.3.0.13

## Semi-auto START arming reliability fix

This build fixes the second-sample failure seen in v0.9.3.0.12.

### Changes
- First and subsequent samples now use the same safe START arming path.
- START no longer installs the full-screen touch capture overlay inside the same click transaction.
- New state flow: `START -> ARMING... -> ARMED -> ACTIVE -> START`.
- A 320 ms settle window isolates the START ACTION_UP/window re-layout from YouTube, preventing the START gesture from falling through to the mini-player.
- `ARMED` is shown only after the touch capture overlay is actually installed.
- If capture installation fails, the UI returns to `START` and asks the tester to retry instead of exposing a false ARMED state.
- LOADED / cancelled attempt / RETRY restore START to an enabled clean idle state.

### Recommended validation
Repeat at least 10 cycles:
1. Navigate/scroll YouTube freely.
2. Tap START.
3. Wait until button changes from ARMING... to ARMED.
4. Tap one target video.
5. Tap LOADED at first frame.
6. Return to the list, scroll, and repeat.

Expected: START itself never opens the YouTube mini-player; every ARMED state has a real capture layer; one START produces at most one T0.

Version: 0.9.3.0.13
VersionCode: 89

## v0.9.3.0.12

# CellTracker v0.9.3.0.12

## Semi-auto manual-per-attempt workflow

This version changes Semi-auto Video Loading testing to an explicit per-sample START/LOADED workflow.

### New workflow
1. Open the desired YouTube video list and scroll/navigate freely.
2. Press **START** in the CellTracker overlay.
3. CellTracker arms touch capture only for the next sample.
4. Tap one YouTube video. The real captured tap is **T0**.
5. When loading is complete, press **LOADED**. The LOADED press time is **T2**.
6. CellTracker calculates `Loading Delay = T2 - T0`, saves the row, and returns from playback.
7. Touch capture remains OFF. Scroll to the next desired item freely.
8. Press **START** again for the next sample.

### Important behavior changes
- No START = no touch capture and no new Attempt.
- Scrolling between tests can never create an Attempt because the capture layer is not armed.
- Page-transition-only fallback T0 is disabled; T0 must come from a real captured click/touch.
- Android Back is no longer accepted as T2. Returning before LOADED cancels the unfinished sample.
- LOADED with no valid T0 shows a prompt to press START and tap a video first.
- Existing Auto mode behavior is unchanged.

Version: 0.9.3.0.12
Version code: 88

## v0.9.3.0.11

# CellTracker v0.9.3.0.11

## Semi-auto YouTube loading fixes

This build focuses on recovery after scrolling and on making RETRY a real state reset.

### 1. Post-scroll tap capture
- Removed the extra 110 ms re-arm delay after a replayed list scroll.
- The transparent touch capture layer is reinstalled immediately after the injected scroll completes.
- Accessibility events are suppressed only while the scroll is actively being replayed, not during an additional settle window.
- This prevents a quick tap immediately after scrolling from opening playback without creating a valid Attempt/T0.

### 2. RETRY hard recovery
- RETRY in Semi-auto now resets unfinished T0/click/gesture/scroll/playback transient state.
- An unfinished Attempt is discarded without affecting already saved rows.
- If RETRY is pressed while YouTube is on a playback page, CellTracker performs exactly one Back and waits for a stable non-playback/list page before re-arming touch capture.
- If automatic return is not confirmed, the tester is asked to press Android Back once; CellTracker then re-arms automatically after the list is stable.
- A generation guard prevents an old scroll replay callback from re-arming stale state after RETRY/STOP.

### Suggested regression sequence
START -> tap video -> LOADED -> return -> scroll -> immediately tap video -> LOADED -> return -> repeat -> RETRY -> tap video.

Expected: scrolling never creates an Attempt; every real video tap creates exactly one Attempt; immediate post-scroll taps are captured; RETRY returns Semi-auto to a clean ready state.

## v0.9.1.0

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

## v0.9.0.10.2

# CellTracker v0.9.0.10.2

Hotfix for the Settings fresh-entry scroll reset compile error introduced in v0.9.0.10.1.

Changes:
- Replaced direct `ScrollState` construction with `rememberScrollState(initial = 0)`.
- Reset Settings root to top with `LaunchedEffect(visitId) { rootScrollState.scrollTo(0) }` only for a new Main -> Settings visit.
- Settings child page -> Back keeps the current Settings root position for the same visit.
- Main page scroll restoration remains unchanged.
- No changes to dark mode, Device Link, Call Setup, dual-DUT network report sync, or Long Call logic.

Version: 0.9.0.10.2 (versionCode 56)

## v0.9.0.10

# CellTracker v0.9.0.10.1

Hotfix for Settings root scroll behavior. Every new entry from the main screen creates a fresh Settings visit and starts the Settings root at scroll position 0, while Back still restores the main screen position. Dark mode and the existing v0.9.0.10 behavior are unchanged.

# CellTracker v0.9.0.10

Changes:
- Settings root always starts from top on each fresh visit.
- Parent/home scroll position is synchronously persisted on navigation disposal, so Back restores the exact prior position more reliably.
- Call Setup local phone number fields use external labels to avoid transient label/value overlap on screen entry.
- Added system-following dark mode for Compose UI plus a night Android window theme.
- Long Call behavior from v0.9.0.9.1 is unchanged for tomorrow's field validation.

Build note: local assembleDebug could not run because this environment cannot resolve services.gradle.org for Gradle 8.7. Use GitHub Actions for full build verification.
