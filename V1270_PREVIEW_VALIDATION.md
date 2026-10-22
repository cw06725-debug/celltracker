# CellTracker v1.2.70 Preview 1 — status and acceptance notes

## Important release status

This is a **source-only, partial field-test preview**, not a finished or compiled v1.2.70 release. Start from v1.2.69-CompileFix. No original files were deleted. Android compilation, installation and device testing have NOT been executed in this environment because there is no Android SDK. Use GitHub Actions to build and capture compiler diagnostics before deploying.

## Implemented and source-inspected

1. **Qualcomm / Transsion Logger** — detect installed `com.transsion.TranLogManager` on Qualcomm hardware, and attempt explicit start of `.TranLogManageActivity` only if Android reports the Activity exported and callable by this app. Retains older QTI candidates and existing MTK Logger logic. ADB `am start` success alone does not establish normal app permissions.
2. **Log D** — Device Logs / ADB Tools has Check, Enable D, Restore. Uses local authorized ADB `getprop` / `setprop` and a follow-up `getprop` for confirmation; restores the previous value stored in private preferences after a verified write. Log D does not start vendor Modem Log; no automatic changes on starting a test. If the previous property was unset, restoring an empty value may be rejected by the ROM; the app reports failure and leaves the saved original value until verified.
3. **Original vendor log export** — existing `/data/debuglogger` ADB Sync export remains; no attempt to read a restricted private system directory directly through normal app file APIs. Transfer success depends on ADB permissions, file contents and OEM device behavior.
4. **Home / navigation** — removed visible bottom `Tests` tab; Home shows expanded test cards directly; no Quick Start / See All / Recent Tests / Weak Coverage tile. No original test business module was deleted.
5. **Icons** — differentiated custom-drawn Ping meter, Call receiver and Network Recording icon.
6. **Test Setup** — shared dialog surface for YouTube, WhatsApp Image Send, Ping Test, Call Setup; previously existing TikTok upload/lag dialog remains. Existing field-level test parameters retained. Additional Test Setup harmonization may be needed after UI testing.
7. **Throughput** — real HTTPS **download** payload bytes and timing, up to 100 MiB per sample, configurable URL, 1–10 samples, CSV export/share, STOP. Network type is not forced; upload throughput and data-SIM binding NOT implemented.
8. **Version** — `versionName=1.2.70`, `versionCode=247`.

## Outstanding from the complete v1.2.70 roadmap (NOT implemented)

- Concurrent **1 Controller + 3 Agents** report transport (legacy Device Link currently handles a single peer).
- Test Session distribution, per-device offline queues, per-report ACK/deduplication, reliable resume after disconnect.
- Multi-device DUT1/DUT2/REF1/REF2 report pairing, comparison rule engine, time-aligned RF analysis, manual overrides and six-sheet Excel summary.
- WhatsApp Call standalone automatic flow and uplink throughput.
- A native unprivileged app-only export from `/data/debuglogger/` (Android sandbox prevents assuming access).
- On-device verification of the new Transsion Logger shortcut (including actual `android:exported`).

## Smoke test sequence after GitHub Actions builds

1. Install APK over prior version; check historical reports and recordings remain.
2. Verify three bottom tabs: Home, Reports, Settings. Home has all required visible cards, no Quick Start/Recent Tests/Weak Coverage.
3. Qualcomm Transsion SM7635: check TranLogManager card appears, icon loads, main UI opens; if absent/denied, capture package Activity exported status and exceptions.
4. MTK handset: check logger auto-recognition and click-through.
5. Wireless ADB: connect Local DUT, Check `persist.log.tag`, Enable D, confirm actual property is `D`, Restore and verify previous value; check behavior after application restart. Independently use vendor logger to capture Modem Log.
6. Export `/data/debuglogger/` with permission; verify pulled files, timestamps and source intact.
7. YouTube / WhatsApp / Ping / Call Setup open their dialogs; verify existing setup parameters, Cancel, Start, history and Stop for running tests.
8. HTTP DL Throughput against a valid HTTPS server: verify realistic received bytes, nonzero Mbps, CSV path and sharing; test invalid URL and cancellation. This sends real metered traffic.
9. Regression: Device Link pairing/reconnection, Reports, network recording, TikTok, SIM switching, and USB detach handling.

Git commands after extracting project:

```
git status
git add .
git commit -m "feat: v1.2.70 preview logger, home, setup and throughput"
git push
```
