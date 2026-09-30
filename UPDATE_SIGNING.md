# CellTracker in-place APK updates

Android preserves CellTracker settings/history during an APK update when all of these stay the same:

1. `applicationId` stays `com.example.celltracker`.
2. The new APK has a higher `versionCode`.
3. Every APK is signed with the same signing certificate.

## One-time setup

Create one keystore and keep it permanently:

```bash
keytool -genkeypair -v -keystore celltracker-update.jks -alias celltracker -keyalg RSA -keysize 2048 -validity 10000
```

Copy `celltracker-signing.properties.example` to `celltracker-signing.properties`, fill in the password/alias, and keep both the real properties file and keystore private.

When the file is present, this project signs debug and release APKs with that stable key. Subsequent APKs can update the installed app directly (or via `adb install -r`) without uninstalling and without clearing SharedPreferences/app-private report history.

## GitHub Actions

Do not commit the private keystore/password to a public repository. Store the keystore as a Base64 GitHub Secret and recreate `celltracker-update.jks` plus `celltracker-signing.properties` in the workflow before Gradle runs. Every workflow run must reuse the SAME keystore.

Important: the first APK signed with the new stable key cannot update an APK signed with an older/different key. There is one final uninstall/reinstall when switching certificates. After that, future versions can update in place.
