# CellTracker v1.2.70 Preview 2 — source changes and field validation

## Release status

**Uncompiled source preview.** Based on v1.2.70 Preview 1, preserving existing Reports, Device Link, logger shortcuts and local test data layout. `versionName=1.2.70`; `versionCode=248` (Preview 1 used 247). No build or handset verification has been performed in this runtime: Android SDK is unavailable. Do not consider this a final multi-device release.

The screenshot about **TransMFT keeps stopping** is a separate application crash and is **out of scope** of this patch.

## Included in Preview 2

1. **Cancel behavior:** When opened from Home, initial YouTube / WhatsApp Image Send / Ping / Throughput Test Setup Cancel now uses the route's `onBack`, returning to Home instead of exposing the older test-history page. These tests continue using their original business logic and report repositories after the user begins a test. The older detail/history screens still exist; the Reports tab remains the intended history entry.
2. **Call Setup immediate dialog:** Moved the shared test dialog out of the offscreen `LazyColumn` item to the root of CallSetupScreen. If controller is connected, test can begin using existing validation. When no controller/Agent connection or SIM numbers are configured, the dialog provides **CONFIGURE DEVICE LINK / SIM**, which dismisses it to expose the existing connection screen. Cancel returns Home.
3. **Consistent Test Setup:** Throughput now uses `UnifiedTestSetupDialogV1270`; TestMetadataDialog uses the same modal surface as other tests (including Network Recording task metadata). Shared sheet uses a visible, consistent Cancel action.
4. **Home icon normalization:** Manufacturer/app icons fit inside the same 46-dp tile as CellTracker-drawn icons, with a 38-dp graphic inset. This is a visual sizing adjustment only; app package detection and opening are unchanged.
5. **Settings icon refresh:** Replace arbitrary Unicode pictograms with Android platform icon resources inside 46-dp blue cards; same sizing as Home icons. Bottom navigation Home / Reports / Settings icons now use pictograms instead of font glyphs.
6. **Reports icons:** Each category card gets a symbol tile (YouTube, WhatsApp, TikTok, Ping, Call, Recording, Weak Coverage); categories, saved count and selection behavior unchanged.
7. **Vendor log import/share without ADB:** In Device Logs, `SELECT LOG FILES` uses Android's documented user-granted file picker (`OpenMultipleDocuments`); selected readable files are copied, unchanged, to the application's `Documents/VendorLogImports`. `SHARE IMPORTED LOGS` sends those copied files with `FileProvider` URIs. State rehydrates from saved imports on screen reopen. Limits: max 20 selected inputs per batch, 512 MiB per file. The app **cannot directly read** private `/data/debuglogger/` via normal app storage APIs. Original ADB Sync export and Log D actions remain intact.
8. **Compile correction precaution:** Only named `onDismiss` callback and shared dialog component changes; no reinterpretation of existing test callback parameter positions.

## Field-test checklist after GitHub Actions succeeds

- Install over v1.2.70 Preview 1; confirm no loss of prior recordings/test history.
- Home > YouTube/WhatsApp/Ping/Throughput > Cancel: directly Home; tap Test Setup and proceed through valid parameters; confirm underlying test functions, reports and export work.
- Home > Call: controller opens Test Setup immediately; unpaired state offers CONFIGURE DEVICE LINK / SIM; Bluetooth pair and SIM save; press Test Setup again to start. Agent role must still display link mode rather than a controller-only setup.
- Home > TikTok Video Lag / Upload retain their existing test logic.
- Home manufacturer apps and custom icons all use 46-dp footprint. Test Settings 7 icons and Reports category icons on real displays.
- Home > Device Logs > SELECT LOG FILES: import a small accessible ZIP/log or TXT; close/reopen Device Logs and confirm files remain listed and shareable; check Android share picker. Check large file failure does not crash and no source files are removed.
- Qualcomm TranLogManager and MTK Logger open as previously verified; Check/Enable/Restore Log D over authorized ADB; export `/data/debuglogger/` using existing ADB option.
- Regression: scrolling in Reports/Settings; Device Link connect/disconnect; screen recording; old CSV/HTML report export; dynamic radio information; USB detach behavior.

## Not yet implemented (original v1.2.70 roadmap)

- 1 Controller + 3 concurrent Agent links; reliable Session distribution, offline report queues and ACK/deduplication.
- Automated 4-device DUT/REF report reconciliation, configurable PASS/FAIL/OBSERVE/N/A rules, RF timestamp correlation, manual conclusion edits and six-sheet Excel workbook.
- Direct no-ADB export of `/data/debuglogger/`: Android sandbox blocks assuming access; only user-provided files can be imported without ADB unless the OEM exposes a permitted export/share interface.
- HTTP upload throughput and integrated standalone WhatsApp Call workflow.

These functions must not be represented as complete. ADB working does not imply an ordinary app has private storage privileges.

## GitHub submission

```bash
git status
git add .
git commit -m "fix: v1.2.70 preview2 setup navigation icons and log import"
git push
```
