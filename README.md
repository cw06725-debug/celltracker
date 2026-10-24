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
