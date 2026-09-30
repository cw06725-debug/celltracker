# CellTracker in-place update signing

v1.2.36 introduces a bundled **internal-test** signing key so APKs produced by fresh GitHub Actions runners keep the same certificate.

## Important one-time migration
APK signatures cannot be changed by an Android update. If the currently installed CellTracker was signed by an older/random debug key, Android will show **package conflicts with an existing package**. Uninstall that old build **one final time**, install v1.2.36, and then keep this signing key unchanged. From v1.2.36 onward, higher-version APKs built from this project can be installed directly over the existing app and app data will be preserved.

Bundled key: `keystore/celltracker-dev.jks` (internal testing only).

For production/Play distribution, replace it with a private production keystore using `celltracker-signing.properties`; never publish a production signing key.
