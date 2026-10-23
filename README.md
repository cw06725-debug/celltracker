# CellTracker — v1.3.0 Preview 4: Report Selection & Compile Fix

Source preview. Version name `1.3.0`, `versionCode` `255`. Android Gradle build and device testing still required.

## Preview 4: Experience Analysis report matching and UI fixes

- Fixed the missing Compose `horizontalScroll` import that broke `ExperienceAnalysisScreenV130.kt:66` Kotlin compilation in Preview 3.
- Reports no longer shows the **Weak Coverage Route** category card; the underlying Weak Coverage test and historical report data have **not** been deleted.
- Experience Analysis top tabs (`Data`, `Wi-Fi`, `Rules`, `Compare`, `Reports`) highlight the selected tab with a solid blue background and white text.
- In **Data**, choose a **From / To** date (including recent dates and dates already represented in report data), then **multi-select** test items, then select *individual matched report runs*. Shows device role, model, optional device ID, original session, recorded time and source. `Select All` / `Clear` affect item choices; a report must still be explicitly checked before it can be analyzed.
- **Apply Selected Reports** provides a non-destructive scoped analysis view for Compare and Reports; exports use **only selected observation IDs**, while original local and Wi-Fi source data remain unchanged. Multiple metrics in one run stay grouped. Multiple runs of the same metric for the same DUT/REF role are flagged instead of silently replacing older attempts; do not aggregate repeated test rounds accidentally. Per-day report selection refuses combined dates; use individual days for Daily or weekly export as appropriate.
- New observations persist optional `deviceModel`, `deviceId`, and `runId`. Local records identify their device when scanned, imported Test Results use the `Device` column and any available `Device ID`, and post-test Wi-Fi imports receive the Agent manifest's real model/device ID. For older records, these fields may show **Unknown**; never invent an identity.
- `Device Model`/`Device ID`/`Test Run ID` appear in the expanded workbook's Raw Data Index; model names are also reflected in new exports. Original Excel source template layout is retained.
- **Load Local Reports for Selected Dates** scans up to 15 days per action; Wi-Fi `Collect + Generate (Full Session)` intentionally remains a separate unfiltered shortcut. For filtered analyses, first Collect All, then apply the selection in Data, then use Compare/Reports.
- Existing configurable Rules are unchanged pending user review. Bluetooth Call Setup and independent four-device field testing remain unchanged.

## Correct workflow: NEVER require 4 phones to connect while testing

1. Test independently on DUT1, DUT2, REF1, and REF2. Each device runs the appropriate CellTracker / MFT business tests and saves its own reports. **No Wi-Fi sync and no multi-device connection is required during the cellular experience test.**
2. Once all tests are complete, put four phones on the same **trusted** Wi-Fi LAN or hotspot. The original Bluetooth Device Link remains exclusively for its pre-existing **Call Setup** workflow; its implementation is unchanged.
3. On the Controller open **Reports → Experience Analysis**. In **Data**, set the exact same Session ID, test date, scenario, operator and RAT on every device; choose DUT1 (or the Controller's actual role). Import external MFT results using the supported template if needed.
4. Each Agent opens the same **Experience Analysis → Wi-Fi** page, chooses its own device role (e.g. DUT2, REF1 or REF2) and taps **Start Sharing**. Keep the Agent's analysis screen open. Copy its local IP address and temporary 12-character pairing code to the Controller. Each Agent runs its own temporary HTTP listener only after the user explicitly starts sharing.
5. On Controller → **Wi-Fi**, enter each Agent's IP, current code and role. Tap **Collect All Configured Agents**, or **Collect + Analyze + Generate Reports**. The latter also imports the Controller's supported local records, executes the saved Rules and produces results workbooks, Daily Word/HTML and Weekly HTML in Downloads. It continues with a clearly flagged *partial report* if an Agent is unavailable.
6. In **Compare**, review the DUT / REF results, radio evidence and manually approved PASS/OBSERVE/FAIL thresholds. Revise a conclusion with the mandatory reason and optional Bug ID. In **Reports**, edit custom daily/weekly narratives and export again if needed.
7. Stop sharing after syncing. Leaving the Experience Analysis screen also stops its temporary Agent listener. Do not uninstall a device's data-bearing app without first backing up its reports.

## Wi-Fi design and data integrity

- Transport: direct local IPv4 TCP/HTTP on port `48731` to a trusted private Wi-Fi/hotspot address. No cloud, Bluetooth, cellular API traffic or permanent report server. Uses Android `Network.openConnection` to route Controller's traffic on Wi-Fi when available; supports hotspot-host local interfaces as fallback.
- A temporary one-time session pairing code is required to request the bundle. The listener is bound only to an available Wi-Fi/hotspot LAN interface, not to cellular. Twelve invalid attempts block new requests until sharing is restarted.
- The Agent creates a snapshot of normalized real measurements, with provenance, time and available Band/PCI/RSRP/SINR. Accessible original reports are attached **without modifying their contents** in a ZIP with manifest and SHA-256 digests. Limits: ~12 MB/file and ~55 MB per bundle; missing/large original files are explicitly counted, not fabricated.
- The Controller verifies metadata, Session ID, DUT/REF role, Report ID, filenames and file checksums before archiving incoming original files in app-private `files/experience_lan_imports/`, then durably commits observations. The same report may be resent; deterministic IDs deduplicate data. Controller sends receipt confirmation only after it has saved the file and metrics.
- Existing locally saved reports remain intact. Bluetooth Call Setup protocol and UI are not modified by this transfer module.
- Controller may collect from three Agents sequentially, with independent IP/code/role configuration. Agent listener sessions are concurrent, so each can be ready at the same time. Auto-discovery/QR and fully unattended device-to-device sync are **not** implemented.

## Important security and acceptance limits

**Preview-only security limitation:** The local HTTP transfer is not end-to-end encrypted. Pairing codes, file integrity checks and LAN restriction are not substitutes for authenticated TLS. Use only an isolated, trusted Wi-Fi/hotspot network; never transfer confidential raw logs on public, shared, or unknown networks. TLS/verified peer identity is a required hardening step before a production-grade release. The manifest has `android:usesCleartextTraffic=true` solely to enable this initial local HTTP transport.

**Supported automatic raw report coverage:** local Ping, YouTube, WhatsApp Image, TikTok CSV and existing Call Setup summaries recognized by Preview 2. MFT results may still need import via the original Results XLSX/CSV template. Import provenance remains explicit and never pretends to be automatically acquired. Private or inaccessible raw source files may be missing from bundles; normalized metrics can still be present.

**Engineering status:** The supplied source has not passed a full Android SDK / Gradle APK build or real four-phone Wi-Fi field test. Do not label it a stable release. Validate Wi-Fi/LAN interfaces and firewall/client-isolation behavior on actual Transsion/vivo/Samsung devices, slow links, retries, duplicate sync, wrong code/role, partial results, and the existing Bluetooth call workflow.

## Analysis functionality carried over from Preview 2

- User-defined per-metric OBSERVE / FAIL thresholds and minimum sample counts, percentage / absolute count / percentage-point differences.
- DUT/REF date, operator, RAT and scenario comparability checks; unverified RAT can only yield OBSERVE; N/A where data is insufficient.
- Original Communication Experience Test Results Excel template and extended analysis workbook.
- Original eight-section English Daily DOCX and editable HTML; W42-style editable Weekly HTML.
- All conclusions remain human-reviewable with reason and Bug ID.

## Signing and building

Use the GitHub Actions workflow or Android Studio to build `assembleDebug`. Keep the same internal testing signing certificate to upgrade without wiping reports. The bundled `keystore/celltracker-dev.jks` is for internal development only, not production. See `celltracker-signing.properties.example` for private signing. Only `README.md` and `CHANGELOG.md` are kept as Markdown in the project ZIP.


## Preview 5: post-test Wi-Fi Direct Controller / Agent

Open **Reports → Experience Analysis → Wi-Fi** on all devices after the cellular experience tests are finished.

1. On one phone, select **Controller** and its DUT/REF role. Set the same Session ID as the other devices, then tap **Start Controller**. Android may display a Wi-Fi Direct permission/confirmation prompt.
2. On each other phone, select **Agent** and its unique DUT/REF role, tap **Find Controllers**, choose the listed **CellTracker Controller** service and tap **Connect**. Nearby ordinary hotspots are not shown in this service list.
3. Once connected, an Agent automatically attempts to package its previously saved reports and register with the Controller. If this cannot complete (missing records, Wi-Fi Direct IP not ready, mismatched Session), review the error and use **Retry Report Sharing** after correction.
4. Controller can see each registered Agent's role, model, and device ID, tap **Collect** individually or **Collect All Connected Agents**, then use **Data → selected dates / multiple test items / exact reports → Apply Selected Reports → Compare / Reports** for analysis and output.
5. If discovery/grouping fails on a device, expand **Manual LAN / hotspot fallback (IP + pairing code)** and use a trusted shared Wi-Fi LAN or phone hotspot. Controller does not need to be an internet gateway: Wi-Fi Direct is a local-only P2P group, not the system's normal mobile data tethering hotspot.

**Requirements & caveats:** Enable Wi-Fi, Nearby Devices permission, and Location access/service when prompted; vendor Android builds differ in P2P group-owner and multi-client support. Phone models must pass a real four-device test. The advertised service type filters unrelated hotspots but is **not cryptographic proof of peer identity**. Existing archive transfer uses local HTTP with a temporary key and SHA-256 integrity checks, not TLS; only use on trusted devices. Stop Agent sharing and Controller group after collection. Bluetooth Call Setup remains independent. No reports are sent while cellular testing is in progress.
