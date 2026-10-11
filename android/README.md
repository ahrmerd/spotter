# Spotter (Android)

A download manager with a built-in browser that spots videos and files on the page. Desktop companion and browser extension live in `../server` and `../extension`.

- Minimum Android 8 (API 26), target 35. Kotlin, Jetpack Compose, OkHttp, Room, WorkManager. No Google Play distribution (APK only).
- Plan, audits and status: `IMPLEMENTATION_PLAN.md`, `reports/`, `docs/ROADMAP_V2.md`.

## Build and test
```bash
cd android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM tests
./gradlew connectedDebugAndroidTest   # needs a running emulator or device (use one, see AGENTS.md)
./gradlew lintDebug lintRelease    # must report no issues
```
Debug builds allow plain `http://` to `10.0.2.2`, `localhost` and `127.0.0.1` (test servers); release builds do not (HTTPS only).

## Release build (minified and signed)
Release builds run R8 (code shrinking and obfuscation) and resource shrinking. The signing key is **never** stored in the repository.

1. Create a key once (keep the file and passwords somewhere safe and backed up; losing it means you can no longer update an installed app):
   ```bash
   keytool -genkeypair -v -keystore ~/keys/spotter-release.jks -storetype PKCS12 \
     -alias spotter -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Tell Gradle where it is, either with a git-ignored file `android/keystore.properties`:
   ```properties
   storeFile=/home/you/keys/spotter-release.jks
   storePassword=...
   keyAlias=spotter
   keyPassword=...
   ```
   or with environment variables `SPOTTER_KEYSTORE`, `SPOTTER_KEYSTORE_PASSWORD`, `SPOTTER_KEY_ALIAS`, `SPOTTER_KEY_PASSWORD`.
3. Build and check:
   ```bash
   ./gradlew assembleRelease
   ~/Android/Sdk/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   adb install app/build/outputs/apk/release/app-release.apk
   ```
   Without a key the build still succeeds but writes `app-release-unsigned.apk`, which Android will not install.
4. Bump `versionCode` (must grow with every release) and `versionName` in `app/build.gradle.kts` before each release.

`*.jks`, `*.keystore` and `keystore.properties` are in `.gitignore`. Never commit them.

## Release checklist
- [ ] `./gradlew testDebugUnitTest connectedDebugAndroidTest lintDebug lintRelease` all green.
- [ ] Install the **signed release APK** (not debug) on a device and run the smoke test: add an HTTPS file link; download an HLS stream and check the `.mp4` plays; open a page with a video in the browser and see "N media"; open Settings; pause/resume a download; kill the app and reopen it.
- [ ] Check the About/version and the launcher icon.
- [ ] Keep `app/build/outputs/mapping/release/mapping.txt` for each released version (it decodes crash stack traces).

## Notes
- Backups are disabled (`allowBackup=false`, `data_extraction_rules.xml`, `full_backup_content.xml`): the database holds download history and browser data.
- Cookies and Authorization headers are never stored; the downloader reads cookies from the WebView at request time (`engine/net/CookieSource`).
- Third-party licences: OkHttp, Room, WorkManager, Compose (Apache 2.0). **NewPipeExtractor (GPL-3.0) is linked for YouTube support (M7), so Spotter as a whole is GPL-3.0: anyone who receives the APK must be able to get the matching source.**
- YouTube: paste or share a watch link, pick a quality. When YouTube changes and it stops working, update the `NewPipeExtractor` version in `app/build.gradle.kts`.
