# CellTracker — v1.3.0 Preview 2 (Experience Analysis)

Android field-testing app for communication experience, network diagnostics, reports and vendor logging.

## Release status

- App version: `1.3.0`; `versionCode` `253` (Preview 2).
- **Preview source only:** pure-Kotlin analysis engine and template processors passed isolated Kotlin compile/regression checks. A full Android `assembleDebug`, device tests and end-to-end imported-report tests must still pass in GitHub Actions / field devices.
- All existing v1.3.0 Preview 1 features and raw reports are retained.
- This update adds a new **Reports → Experience Analysis · Daily / Weekly** entry.

## Analysis workflow

1. In **Reports → Experience Analysis**, choose Session ID, test date, test scenario, operator, RAT and role (`DUT1`, `DUT2`, `REF1`, `REF2`).
2. Tap **Load My Local Reports** for supported native records; assign the actual local device role first. The scan covers supported local Ping, TikTok CSV, YouTube, WhatsApp Image and Call records from the selected date. Original reports are never modified.
3. For external test phones, import the supplied `Communication Experience Test Cases and Results V1.0` Excel workbook/`Test Results` CSV. The `Role (DUT/REF)` column must explicitly hold `DUT1`, `DUT2`, `REF1` or `REF2` (generic `DUT`/`REF` is ambiguous and skipped). Alternatively **Add measurement manually**, always labelled `MANUAL`.
4. In **Rules**, enable the applicable individual metric and configure OBSERVE and FAIL thresholds and minimum attempts. Relative latency/upload/loading differences use **%**, stall counts use **absolute count**, and success rates use **percentage points**. Until enabled, a rule cannot silently declare FAIL or PASS.
5. In **Compare**, review DUT versus matching REF and optionally enter a manual assessment **with required justification** and Bug ID. The automated verdict is preserved alongside the final verdict.
6. In **Reports**, customize narrative fields and export:
   - **Communication Experience Test Result:** original four-sheet supplied XLSX template when the workbook fits its 100-row capacity **plus** a nine-sheet analysis workbook.
   - **Daily Report:** original supplied 8-section English Word (`.docx`) template filled with the selected session, plus an HTML preview.
   - **Weekly Report:** editable HTML following the five principal sections of the prior W42 weekly report; covers the seven days ending on the selected date.

Files are stored under `Downloads/CellTracker/<date>/Analysis` through Android MediaStore.

## Important accuracy limits

- **Automatic 1 Controller + 3 Agents transfer/synchronization is not yet implemented**. Transfer comparison results by importing exported worksheets / CSV or explicitly labelled manual entry.
- Results are evaluated only when **date, scenario, operator and RAT** are comparable. Missing observations/REF are `N/A`; unverified user-declared RAT is `OBSERVE` at most. RSRP/Band/PCI/SINR are *context*, not proof of a root cause.
- Field app reports have different schemas. Not every existing test type is auto-parsed; MFT Ping, MFT Short Video and dedicated VoLTE/WhatsApp Call are distinguished from CellTracker native tests and may need template import/manual input.
- Rates do not imply exact raw successful attempts. The exporter does **not** reconstruct or invent success counts from rounded percentages.
- Weekly output is HTML, not the exact Word/PDF layout. Visual/end-to-end field validation is pending; no fake sample results are shipped.
- Keep the same Android signing certificate to update an installation without losing stored reports. Do **not** uninstall a data-bearing copy without backing it up.

## Build

Use the GitHub Actions workflow or Android Studio's `assembleDebug`. Verify all active Compose screens, Android MediaStore exports and imported templates on actual devices. A build success does not confirm multi-device compatibility.

## Signing

For test builds, existing `keystore/celltracker-dev.jks` is for internal use only. Use the same certificate to update previously installed builds. Configure private signing credentials through `celltracker-signing.properties` as explained in `celltracker-signing.properties.example`; never publish production keys.

## Changelog

See `CHANGELOG.md`. Deliberately no historical preview-validation Markdown files are included in this project archive.
