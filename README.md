## Preview 28 (versionCode 279) — Per-event video review sync

- Report event Review Video now passes report identity and event index to the player. START/END adjusts that event's T0/T1, and CONFIRM REVIEW writes changes to the report-specific review store, not the unrelated video-only marker list.
- Returning to Summary reloads saved edits on lifecycle resume and refreshes reviewed totals in the summary.
- Video player's EXPORT REPORT is hidden when reviewing a report event. Confirm all events and export once from the Summary screen; original XLSX remains unchanged.
- PREVIEW: Android Gradle build and real-device verification remain pending. Timeline is estimated.

Preview 27: PUBG/Tamasha per-event review with manual millisecond T0/T1 edits, invalidation, confirmation and audited export. Full Gradle build and device validation pending.

## Preview 20 (versionCode 271) — Reports-first review and smooth video clock

From Reports → PUBG Gaming / Tamasha Live Streaming, select an exported report to view its **in-app Summary** and per-stall times before choosing REVIEW RECORDING (for its associated latest video) or OPEN EXCEL REPORT. Pending latest video without exported results appears as **Latest recording · Review pending** under its test category. The generic **Reports → Screen Recordings · Timeline** route also starts with a summary instead of immediately playing the video. The test setup page no longer contains a recording-review launcher or a direct-XLSX saved-report list.

Video playback shows smoothly interpolated milliseconds (40 ms UI tick, monotonic time) and can still be paused/seeked. This interpolation is for display only; the actual video/video-log mapping remains **ESTIMATED**, not frame-accurate. Original files, event markers and exports are preserved.

**PREVIEW only:** local syntax checks and isolated position tests are not a full Android Gradle build; verify via GitHub Actions and real-device playback/navigation.

---

# CellTracker v1.3.0 Preview 19 — CompileFix (Player, Report Actions & Video Diagnostics)

**PREVIEW only · versionCode 270 · based on Preview 19.** Screenshot-reported `:app:compileDebugKotlin` errors in the player ScrollView setup have been addressed; full Android Gradle compilation and real-device verification are still required.

- **CompileFix:** `RecordingTimelinePlayerV130.kt` uses `ScrollView.setFillViewport(true)` and `setVerticalScrollBarEnabled(false)`, plus `FrameLayout.LayoutParams(...)` for its child. Also names the gradient `rootBackground` explicitly to avoid ambiguous property references. No player transport/annotations or export logic changed.
- Validation: full project Kotlin PSI syntax check; focused Kotlin/Java type smoke test for the changed Android View call pattern; XML/ZIP integrity. **Not an Android Gradle build.**

- **Recording Review** rebuilt as a proper in-app player: compact adaptive-aspect video area (no giant unused black screen), back/title header, play/pause, ±5 seconds, draggable seek bar with elapsed/duration, estimated local-clock overlay, and separated stall annotation section (START / END / UNDO / EXPORT). All cards and controls use the existing soft lavender/blue/mint gradient family. Playing and seeking use the original MP4, which is never modified.
- **Report detail actions** now use a shared primary/secondary gradient action component in the Reports TikTok/YouTube/Ping/Call/WhatsApp and other main report-detail paths, including preview, export, confirm, play, and delete. Existing callback logic and report contents are retained. Top app-bar navigation and confirmation-dialog controls are intentionally not bulk-replaced.
- **Settings → Video & Diagnostics → Video diagnostic logs** is a persisted switch **OFF by default** on new/existing installs. When OFF, a NEW CellTracker screen-recording session does not create or publish `screen_recording_diagnostics/*.txt`, does not enumerate AVC encoders for diagnostics, and suppresses that optional verbose diagnostic logging. When ON, the previous engineering TXT capture/export behavior is retained. Each recording snapshots the switch state at START. Old diagnostic files are not deleted by switching it off.
- **Never gated:** MP4 recording, TimeMap.csv, SRT, test metadata, core recording errors/status, timestamp correlation, manual stall reviews, and XLSX/CSV reports operate regardless of diagnostic switch. Optional playback/seek/error engineering Logcat entries are also guarded by the same switch.
- Previous Preview 18 Home live reorder, soft gradient theme, external video matching, Wi-Fi Direct, Shizuku, Speedtest, Bluetooth Call Setup and all tests remain unchanged.
- Validation performed: Kotlin PSI syntax for all 5 touched/new Kotlin sources, preference/diagnostics static branch assertions, Android XML parsing and archive CRC. **Not a Gradle build or on-device playback verification.**

# CellTracker v1.3.0 Preview 18 — Live Launcher Reordering

**PREVIEW only · versionCode 269**. GitHub Actions Gradle build and real-device behavior are not verified in this environment.

- Home: iOS-style live insertion. A floating dragged card follows the touch; other cards shift one slot immediately on crossing the target; release only settles the floating card into the blank slot. Absolute/root-coordinate motion prevents positional jumps while the keyed grid animates. Back and DONE still save/exit. Stable test IDs and history are untouched.
- Shared low-saturation lavender/pink/mint visual style: category-accented Home and Reports cards, Cell Info detail cards, PUBG/Tamasha setup actions and native post-recording review panel. Primary gradient buttons use violet→blue→mint; secondary use soft pastel fill. Third-party installed app icons stay unchanged. Accent tint is static per test category to avoid expensive icon pixel extraction during 1-second telephony updates.
- External/system screen recording: start/end a test-window timer independently of CellTracker MediaProjection; use "FIND MATCHING RECORDINGS" (requires media permission) or "SELECT SYSTEM VIDEO" (Android file picker) to pick video. Read MP4 duration and available timestamp metadata, show estimated start time to edit/confirm. On confirmation, save separate per-second TimeMap.csv and .srt under Downloads/CellTracker/.../ExternalTimeline, keep the original MP4 URI and open existing video review/manual stall markers.
- All externally inferred timestamps are **ESTIMATED**, never frame-exact. DATE_TAKEN can be metadata-derived and DATE_ADDED/DATE_MODIFIED are not reliable capture start times. All candidate matches require human verification; visible event synchronization is advised for modem-log analysis. Origin is marked EXTERNAL_VIDEO_ESTIMATED / EXTERNAL_VIDEO_REVIEW_ESTIMATED. Recording through CellTracker continues to use its own elapsedRealtime/wall-time anchors.
- Existing Wi-Fi Direct, Shizuku, Speedtest, Bluetooth Call Setup, data collection, reports and gradient launcher icon retained. All-screen button replacement is **not complete**; this preview standardizes the prominent flows shown in feedback rather than changing 200+ UI controls indiscriminately.
- Static Kotlin PSI and Android XML checks, ZIP CRC pass. Full Gradle build unavailable because this source bundle has no usable Gradle Wrapper JAR, and Android SDK is absent from the local environment.

---

## v1.3.0 Preview 16 — Home Layout & Post-Video Stall Review (versionCode 267)

- Reports classification uses the same icon-rendering pipeline as Home. PUBG/Tamasha show the installed app icon; fallback Ping/Call/Network tiles share the same vector symbols as Home. Installed icons use FIT_CENTER, avoiding clipping.
- Home supports long-press edit mode, subtle edit-only wiggle, drag-to-reorder (hold then drag any tile), DONE, and persistent order on the current phone. Test IDs and report paths remain unchanged. Hidden vendor loggers keep stable IDs.
- PUBG/Tamasha manual overlays have short single-line START / END / FINISH buttons, without changing event timing.
- Video-only (no overlay) recordings can be reviewed after capture. In Reports → Screen Recording · Real-Time Playback or the test's Review button, pause/seek then MARK START / MARK END, repeat for multiple stalls, UNDO, and export reviewed stall CSV + XLSX. Review events persist for the last recording; original MP4, SRT and TimeMap.csv are never modified. Export is marked VIDEO_REVIEW_ESTIMATED and duplicate exports of the same markers are blocked. Use the saved device/role/task/starting network snapshot for provenance.
- Time alignment is estimated from MediaRecorder clock anchors; no exact first-frame PTS calibration. Video-only mode does not automatically detect visual stalls. Reports are manually reviewed observations, not automatic frame detection.
- Retains Preview 12/13 navigation stability, Settings slide, gradient icon, Wi-Fi Direct, Shizuku, Speedtest and other test features.
- Static Kotlin PSI parsing and a dedicated 4-case Home ordering test passed; full Android Gradle assembleDebug and real-device validation have NOT been performed in this environment.

## Preview 15 — Compile Fix + Recording Time Map (versionCode 266)

- Fixes Kotlin compile errors in `MainActivity.kt` (`ReportListCard` click parameter) and `ManualStallTestV130.kt` (`Button.text` assignment shadowing).
- PUBG/Tamasha Manual Data Experience Test supports **Video only · no overlay** as an alternative to the existing manual stall floating control. Each screen recording requires Android MediaProjection consent. Recording can be stopped by the ongoing foreground notification action or from the test page.
- Existing `TestScreenRecordingService` now samples wall-clock / elapsedRealtime each second. After a valid MP4 is saved, two **companion files** are written beside it under `Downloads/CellTracker/.../Screen Recordings/`: `<video>_TimeMap.csv` (every video second mapped to local time + epoch ms) and `<video>.srt` (playback time-clock subtitle file).
- `Reports → Screen Recording · Real-Time Playback` opens the latest CellTracker time-mapped video in the built-in viewer and overlays local time at the current playback position. The underlying MP4 remains unchanged, and ordinary gallery players do **not** automatically embed/burn-in the subtitle; use the built-in viewer or a media player that can load SRT.
- Time-map accuracy: **estimated**, not frame-exact. MediaRecorder does not provide a direct wall-clock timestamp for the first encoded frame, and dropped/shifted frames or recording start latency can affect mapping. Video length is read from saved MP4 and correlated with monotonic recording start/stop clocks; periodic wall-clock anchors are saved to handle clock changes. Validate important log timestamps against a visible reference event.
- Video-only mode does not mark or detect stall events. Use original overlay mode for manual live stall marking; future post-test mark/review UI can be added separately.
- Unchanged: Bluetooth Call Setup, Wi-Fi Direct, Shizuku, Speedtest, existing report analysis, Home/Settings animation and gradient launcher resources.
- Validation: source Kotlin PSI/XML/ZIP checks only; full Gradle assembleDebug and on-device video recording remain necessary.

# CellTracker v1.3.0 Preview 14 — Soft Page Motion + Manual Stall Tests

**Test preview** · `versionCode=265` · `versionName=1.3.0` · based on Preview 13 Home Motion. Full Android Gradle compilation and real-device testing are **not verified in this environment**.

## Animation polish

- Extend Home's 320 ms eased fade/9 dp upward entrance to Home return, Reports, Cell Info, Settings body, report categories, Experience Analysis, and the Ping/Call/YouTube/WhatsApp/Speedtest test screens. Entering **and returning** to a page triggers one entry reveal.
- Keep Preview 12's flicker-free static bottom navigation icons and instant tab replacement (no simultaneous full-screen heavy tab crossfade). Keep Settings' **working horizontal slide** for subpages, with a subtle content reveal within the slide.
- Settings menu items and report rows get subtle 0.985 pressed-scale feedback. RF readings continue at their original sampling cadence; no infinite gradient animation runs in the background.

## Two new tests using ONE manual-stall implementation

- **Tamasha Live Streaming** — official Android package `com.spbtv.mobilinktv` (Pakistani Tamasha streaming service).
- **PUBG Gaming** — main PUBG MOBILE package `com.tencent.ig`, with extra known market variants for detection.
- Home displays both test tiles. Tap to configure **Task name** and **DUT1/DUT2/REF1/REF2** and see the installed application/version. If missing, use the install link. Select **START TEST** to show a draggable, lilac/mint floating panel; open the target app separately. The floating controls are **START STALL → END STALL** for each observed buffering/freeze event, then **FINISH TEST**.
- System overlay permission is explicitly requested. Android may block or hide overlays on certain protected/fullscreen apps and devices; validate on actual PUBG/Tamasha. The user must tap event boundaries; this is **not automatic FPS, video-buffer, ping or frame-freeze detection**.
- The shared stopwatch uses monotonic time for each completed event. A stall left open when finishing is **excluded**, not invented. Counts, total stall duration (seconds), elapsed test time, stall-time ratio, per-event starts/ends, task, device role, app version and a **test-start-only** operator/RAT/band/PCI/RSRP/SINR snapshot are retained.
- Export creates a raw summary+event **CSV** and a two-sheet **XLSX** (Summary and Stall Events) at `Downloads/CellTracker/YYYY-MM-DD/ManualStall/`. Source files are not rewritten during analysis. Export runs on an IO coroutine.
- Reports → Tamasha Live Streaming / PUBG Gaming shows XLSX history. Experience Analysis → Data → Load Local Reports can import `TAMASHA_STALLS`, `TAMASHA_STALL_SEC`, `PUBG_STALLS`, `PUBG_STALL_SEC`; these are distinct selectable test items in Data and distinct configurable entries in Rules. For a single role+metric with multiple report runs, the existing conflict warning prevents silent merging; choose the exact run before comparison. Wi-Fi post-test bundles reuse the existing Experience Analysis report transfer, including attachments when accessible.
- The captured radio metadata is a **start snapshot**, not proof of the network state throughout the entire stall; manual mark timing is subject to human reaction latency. No simulated results.

## Known limits / validation required

1. GitHub Actions: `./gradlew :app:assembleDebug` with Android SDK 34, then install on same-signature device; this environment only performed Kotlin PSI syntax and pure timing/CSV tests.
2. Test active overlay on PUBG full-screen and Tamasha, drag, repeated START/END, FINISH while no stall and with an unfinished stall, and rotate/background/return cases. Check foreground-service notification and output `CSV`/`XLSX` against manual timestamps.
3. Test both new categories in Reports, scan local metrics in Experience Analysis, multi-select by date/test, and 4-device Wi-Fi report collection. Do not claim existing Wi-Fi Direct multi-client stability is solved by this change.
4. Regression: Home/Reports/Settings rapid tab switching (no nav icon flicker), Settings subpage enter/return slide, test setup and report detail enter/return, and jank/frame times.
5. If Android kills the capture service mid-test, incomplete in-memory events cannot be finalized as a normal report; the app displays an interrupted status. Further crash-resume persistence and always-on network snapshots are not part of Preview 14.

Only README.md and CHANGELOG.md are intentionally retained as root Markdown documentation. All other test/communication modules and the approved gradient launcher icon are preserved.

---

**Current test build: v1.3.0 Preview 13 (versionCode 264).** Home-only one-shot staggered entrance and card press feedback; bottom navigation and Settings transitions retained from Preview 12. Requires GitHub Actions Android build and device verification.

**Preview 12 (Gradient Icon Approved)** — versionCode 262. Replaces the legacy, circular and adaptive launcher icons with the approved lavender–blue–mint gradient artwork. No functional, Wi-Fi, animation, overlay, logging or report changes. Requires GitHub Actions Android Gradle build and launcher verification on real devices.

**Preview 10** — soft-gradient motion and icon update (versionCode 261). Gradual 260ms eased fades with 85ms offset, non-flashing static bottom icons, themed floating overlays and launcher assets. Existing Tests, Wi-Fi Direct, Shizuku, Speedtest, Reports and Bluetooth Call Setup remain unchanged. Needs full Android Gradle build and device verification.

# CellTracker v1.3.0 Preview 9 — Soft Gradient UI + Performance Pass 2

**TEST PREVIEW ONLY** · `versionCode=260` · `versionName=1.3.0` · based on **Preview 8 CompileFix** (not the earlier unpatched Preview 8 archive).

## Visual update (bunnylock-style reference, CellTracker identity)

- A soft lilac / blush / mint palette replaces the heavy blue presentation. Main dashboard, test tiles, Cell Info summaries, Reports categories, settings groups and shared info cards use large rounded, lightly gradient-filled surfaces.
- Home title retains **CellTracker** and differentiates “Tracker” with a calm teal accent; all test buttons, actual RAT/band/RSRP/SINR values and reports are preserved.
- Tab selection now interpolates its background color; full-page slides for heavy composables are replaced by a short **crossfade**. Gradients themselves are cached/static rather than running an infinite animation that would compete with live radio monitoring.
- Both system light and system dark appearances have coordinated palettes. OEM-specific log and throughput icons are unchanged.

## Performance pass 2 (targeted, not a guarantee of zero jank)

- The Home page **caches OEM Logger package detection** rather than repeatedly querying PackageManager at each telecom state refresh. Existing home app-version caches are retained, and the other compact test card now caches its version lookup.
- Native app icons stop unnecessarily calling `ImageView.setImageDrawable` on each AndroidView update if their drawable is unchanged.
- Reports receives a stable history-only snapshot (`recordings`, `pingHistory`, `callHistory`), instead of a new object carrying continuously changing signal trends and elapsed counters.
- The SIM pager selection effect keys off SIM subscription identifiers instead of the entire modem result object, avoiding repeated effect cancellation/relaunch on every signal update.
- Selected recording list and Ping history refreshes now do file IO on `Dispatchers.IO`. The Preview 8 work of sampling radio info on IO and loading TikTok details asynchronously remains intact.
- Heavy two-page slides replaced by shorter fades (160/105 ms) to reduce transitional layout/draw workload. No continuous shimmer or infinite background gradient shader animation.

## Compatibility and tests

- Original Tests, Speedtest (DL/UL), Shizuku, Local ADB, Bluetooth Call Setup, Wi-Fi Direct and the existing DUT/REF report analysis and export APIs remain present.
- These are **source changes**, not a verified performance result. Full Android SDK Gradle compilation and real-device frame-time / scrolling / test-function checks are required. This environment has no installed Android SDK; Kotlin PSI parser passed for all modified Kotlin files.
- The post-test three-Agent Wi-Fi Direct reliability issue remains dependent on actual four-device testing and is not claimed resolved by a UI refresh.

**Recommended GitHub build:** `./gradlew :app:assembleDebug` (using the project’s GitHub Actions setup).

---

> **Preview 8 CompileFix:** Corrects four Kotlin smart-cast compile errors in TikTok Lag/Upload report details. Based on Preview 8 (`versionCode=259`); all other features preserved. Please run GitHub Actions `./gradlew :app:assembleDebug` to verify the full Android build.

# CellTracker v1.3.0 Preview 8 — Shizuku Export, Speedtest and Performance Pass 1

**TEST PREVIEW ONLY** · `versionCode=259` · `versionName=1.3.0` · based on Preview 7 Wi-Fi Direct stability fixes.
**Android Gradle build and real-device testing are NOT completed in this environment.** Existing Wi-Fi Direct 1+3, Bluetooth Call Setup, Rules, Wi-Fi report collection, and Preview 7 source are preserved.

## Device Logs changes

- Removed the nonfunctional **Vendor logs / Import / Share without ADB** UI card. It is not the actual protected-path export mechanism.
- Shizuku UserService retains its explicit authorization and strict allowlist. `getprop persist.log.tag` now has a second read-only Android system-property retrieval attempt if the binary reports a blank value. A genuinely unset value is still reported as unset. Setting D must be verified by reading the property again; no false success.
- Added **EXPORT ORIGINAL LOGS VIA SHIZUKU**, gated by authorization. It enumerates `/data/debuglogger/`, reads source files in max. 64 KiB AIDL chunks and writes a new ZIP to **Downloads/CellTracker/Vendor Logs**. The source files are never deleted or modified. Export fails explicitly if OEM permissions/SELinux deny access or limits are exceeded: max 1200 files, 256 MiB per file, 512 MiB total; this is a conservative field-test cap, not proof that all large modem logs will export. Use existing Local ADB export if Shizuku lacks access.
- **Shizuku is still a separate required app and service.** Its library API is embedded in CellTracker, not the privileged Shizuku server itself. This revision does NOT provide a third-party-free or reboot-persistent privileged server. Android's initial ADB/root authorization rules remain in force.

## Third-party Speedtest workflow

- Home **Speedtest** tile shows the installed app/version, or opens Google Play when not installed. Supports normal and Google-Play-region-specific Ookla packages.
- Speedtest setup: customizable task, DUT1/DUT2/REF1/REF2 role, 1–10 rounds (default 3), optional user-approved screen recording, and simultaneous CellTracker network recording (when SIM/network permissions allow).
- Opens Ookla Speedtest. After each real run, return to CellTracker to **complete the round**, confirm the real displayed **DL / UL Mbps** values, and save measured network cell information. No made-up throughput. Optional user-authorized **Speedtest-only Accessibility reader** attempts label-and-unit text detection. It never touches other apps, clicks, or captures raw text persistently. Extracted numbers are only candidates; user must verify. It can fail on custom/canvas UI, and manual entry always works.
- Saves original-per-round CSV and single-sheet XLSX to **Downloads/CellTracker/YYYY-MM-DD/Speedtest**; speed average is computed from actual entered rounds. Both `SPEEDTEST_DL_MBPS` and `SPEEDTEST_UL_MBPS` are integrated in Experience Analysis rules, multi-select Test Item filters and supplementary output. Use Data → Load Local Reports after testing to bring those two metrics into the DUT/REF comparison. Speed Mbps is stored as text in the Notes column of the original historic template, since the original template has no dedicated speed column; it is numeric in the supplementary analytical XLSX and Speedtest's own XLSX.
- Android MediaProjection screen recording always needs explicit consent for each session; one capture session is used across the Speedtest rounds, not silent recording.

## First performance pass

- The every-second dual-SIM/OEM telephony update loop now runs on `Dispatchers.IO` rather than the Compose UI/main thread, keeping configured measurement cadence.
- Expensive TikTok report-detail parsing for Lag and Upload now runs once per report URI in a coroutine IO context instead of parsing the report synchronously on every recomposition.
- **Jank is NOT certified fixed.** The full-AppState root Compose subscription, other report parsing, long lists and OEM RIL callbacks still require real device Perfetto/JankStats profiling and incremental fixes. Do not reduce actual RF/test sample cadence merely to hide UI slowdowns.

## Suggested verification

1. GitHub Actions: run `./gradlew :app:assembleDebug` and ensure no manifest/AIDL/Compose errors; install over previous Preview using the same development certificate.
2. Device Logs: Shizuku check UID/permission; Test Check Log D → Enable D → Check Log D → Restore; on each MTK/Qualcomm device, compare `adb shell getprop persist.log.tag`. Test Shizuku export with a **small stopped original logger session** first. Ensure the ZIP opens and byte sizes/timestamps correspond to source. Check explicit permission failure and fallback on restricted phones.
3. Speedtest: verify installed version/Not Installed Play Store behavior, 3 round completion and rounding, screen permission/MP4 recording, saved per-round CSV/XLSX and network recording; compare captured values to actual Ookla UI. Accessibility candidate depends on OEM/Speedtest version. Confirm Data Load Local Reports recognizes two Mbps metrics and Compare does not merge runs across different operators/RATs.
4. Performance: compare Preview7 vs Preview8 Home scroll, Report page, Device Logs, ongoing measurement, two-SIM CPU/jank, and 4-phone post-test Wi-Fi. No claim of four-device Wi-Fi acceptance yet.

**Build/status checks performed here:** Kotlin PSI syntax validation for touched source, Java Shizuku service compilation with a test stub, XML parsing, 4 pure Speedtest parser checks, pure Analysis + DUT/REF integration test. These are NOT the same as Android SDK Gradle compilation or real-device testing.

Only README.md and CHANGELOG.md are retained as root Markdown files. Temporary validation scripts and test logs are intentionally excluded from the ZIP.

---

# CellTracker v1.3.0 — Preview 7 Wi-Fi Direct Connection Fix

**Source preview** · `versionCode=258`, `versionName=1.3.0`. Based on Preview 6 (Shizuku Access). Preview 5/6 previously compiled in the user's GitHub Actions flow, but **this Preview 7 source has not yet passed full Android Gradle compilation or a real 1+3 phone Wi-Fi test**. It is not a stable release.

## Exact workflow

1. Finish all cellular-experience tests independently on DUT1, DUT2, REF1, REF2. The phones do **not** need a data connection between each other during testing. Existing Bluetooth Device Link remains only for **Call Setup**.
2. On all four phones open **Reports → Experience Analysis** and select the same **Analysis / output Session ID** (in Data) and matching test date. The Controller must set its own actual role; each Agent selects a unique DUT/REF role.
3. On the Controller, open **Wi-Fi → Controller → Start Controller**. Wait for `Controller ready` and `Registration server: Ready`. A P2P group being formed is **not** enough to confirm that the app's report-registration server is ready.
4. On each Agent, choose **Wi-Fi → Agent → Find Controllers**, choose the advertised CellTracker host, tap **Connect**, and approve the system dialog if shown. Join one Agent at a time initially; check the Controller's **P2P group members** and **Registered Agents** counters after each connection. Three simultaneous clients should be evaluated on real hardware before claiming support.
5. On successful P2P connection, the Agent first registers its presence. If no local reports match the selected date/role, it can still appear as registered but with `Connected, no report yet`. Correct the local report selection and tap **Retry Report Sharing**. When report sharing succeeds, Controller shows `Report ready` and enables **Collect**. An Agent with a mismatched Session ID will be rejected; correct both Session IDs and retry.
6. Controller taps **Collect** or **Collect All Connected Agents** to receive actual reports. The Controller then goes to **Data → date range → multi-select test items → select report runs → Apply Selected Reports**, followed by **Compare / Reports** to generate Excel, English Daily Word and Weekly HTML.
7. After collection, tap **Stop Sharing** on Agents / **Stop** on Controller. If P2P fails to support three peers on the specific OEM devices, use the retained **Manual LAN / hotspot fallback (IP + pairing code)**; this is a separate local network mode, not the same as Wi-Fi Direct discovery.

## Preview 7 reliability changes

- The Controller registration TCP server no longer depends on the `groupOwnerAddress` reported to the Group Owner (it may be anonymized on recent Android). It waits for and binds specifically to an actual active P2P IPv4 interface.
- `requestConnectionInfo` and `requestGroupInfo` track actual P2P connection/group membership, separately from registered Agent identities and report-ready status.
- Registration supports a presence-only handshake before a report exists; missing local reports no longer masquerade as a Wi-Fi connection failure.
- Agent registration explicitly uses the Wi-Fi Direct source IP. Wi-Fi Direct report sharing binds to the P2P interface rather than accidentally selecting a normal WLAN address.
- Joining stops ongoing service discovery before `connect` to reduce OEM negotiation conflicts; repeated scans clear previous discovery requests; a concurrent `Connect` tap is ignored until the outstanding attempt is resolved.
- `Disconnect`/`Stop` releases a P2P group on both Controller **and Agent** devices, waits for asynchronous release when restarting Controller, and guards callbacks against stale generations. A lost Android P2P manager channel is recreated for subsequent user retries.
- Controller can keep three independent Agent records keyed by stable Device ID, reject duplicate device roles, and collect only Agents whose report share is ready. Old records may remain after an Agent drops unexpectedly; Controller can use **Forget** on a stale Agent record and allow that role to re-register without restarting the other peers. A failed fetch must be treated as offline/partial rather than silently successful.
- `No local reports for selected date / role` is now a report-readiness warning, not a claim that P2P group formation failed.

## Verification and remaining limits

**Completed in this source session:** Kotlin compiler type-check of the P2P module against isolated Android API stubs; Kotlin PSI syntax parsing of the three modified modules; simulated application protocol with one anonymized Group Owner and three Agent registrations, including a client disconnect that does not drop other clients; ZIP structure integrity inspection.

**Not completed:** full `assembleDebug` using the actual Android SDK, real four-phone Wi-Fi Direct interoperability, prolonged reconnection soak, and large-archive transfer across all devices. No code-only fix can guarantee a specific phone/OEM Wi-Fi Direct driver will allow three clients simultaneously. Record each phone's model/Android version, the exact failure code (`0=ERROR`, `1=P2P_UNSUPPORTED`, `2=BUSY`), P2P group members count, registered agents count, and Agent's report-ready state during testing.

**Security:** the current trusted-local-LAN/Direct report data path is HTTP, not authenticated TLS. The DNS-SD service filter excludes ordinary unrelated hotspots from the in-app list but is **not** proof of controller identity. Use an isolated trusted test environment; do not transfer sensitive logs on public networks.

## Preserved modules

- Existing Bluetooth Device Link / Call Setup is unmodified.
- Shizuku API and Local ADB fallback from Preview 6 are unmodified. Shizuku supports allowlisted Log D operations and `/data/debuglogger/` access probing, **not direct Shizuku export of the protected log directory**.
- Reports, multi-select test-item selection, configurable Rules, network evidence, original Excel template export, editable Daily DOCX and Weekly HTML remain. Original raw reports and device history must remain intact.
- Known performance/jank improvements and further Rules UI changes are **not** part of this Wi-Fi-only fix.

## Build / signing

Use Android Studio or GitHub Actions with `./gradlew :app:assembleDebug`. Keep the same internal-test signing certificate (bundled test keystore or your own `celltracker-signing.properties`) for in-place upgrades without losing saved reports. Do not use the bundled development keystore for production distribution.

Only `README.md` and `CHANGELOG.md` are intentionally included as root Markdown documentation. External verification logs/scripts are not packaged.


Preview 12: stable app-owned bottom navigation icons and Settings horizontal slide fixes. Verify on a device with fast Home/Reports/Settings switching and Settings subpage back/forward; full Gradle build required.


Preview 24: Manual stall overlay millisecond display and finish confirmation; PUBG/Tamasha setup modal. Other test setup dialog gradients pending unified component migration. Full Android Gradle build and device tests not performed in this environment.

### Preview 30 UI work (partial)
The common setup dialog and TikTok setup entry points share a gradient container. Existing test behavior is unchanged. Full UI audit/migration and Gradle/device validation are still required.

Preview 31: TikTok dialog nested-scroll crash fix and shared setup gradient actions; Gradle/device validation pending.

Preview 32: Test Setup button, switch, radio, chip and checkbox visual alignment. Compile/device validation pending.
