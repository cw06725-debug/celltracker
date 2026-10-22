# CellTracker — v1.3.0 Preview 1 (Compile Fix)

Android field-testing application for communication experience, device information, testing, reports and vendor logging.

## Current status

- Version: `1.3.0` (`versionCode` 252).
- This ZIP fixes six Kotlin type mismatches introduced by the v1.3.0 floating-overlay styling changes.
- It retains the v1.3.0 Preview 1 source features and test data compatibility.
- Full Android Gradle compilation and real-device regression testing must be completed through your GitHub Actions pipeline.
- Multi-Agent reporting / automated comparative analysis remains planned work, not a delivered feature.

## Build

Use the existing GitHub Actions workflow or open this project with Android Studio and build the debug APK. The GitHub Actions artifact normally contains the resulting APK.

## Signing and updating

The existing `keystore/celltracker-dev.jks` is for internal test builds only. Keep the *same* signing certificate when installing updates over an existing CellTracker installation; a differently signed APK cannot replace the installed app. Do not uninstall a version containing important test data without first backing it up. For production distribution, supply your private release keystore via `celltracker-signing.properties` (see `celltracker-signing.properties.example`); never distribute a real production keystore.

## Release history

See `CHANGELOG.md`. Older per-preview validation Markdown files are intentionally omitted to keep the project ZIP clean.
