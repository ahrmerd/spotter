# Spotter: Android app plan

> Superseded by `IMPLEMENTATION_PLAN.md` (the source of truth). Kept as a short overview.

Same idea as the desktop version: a download manager + something that spots videos on the page.
On Android the "extension" is a built-in browser (WebView), like 1DM.

## Modules (package `dev.ahmed.spotter`)
- `engine/`   download logic, no Android UI. OkHttp gives HTTP/2 for free (desktop needed curl for that).
  - `HttpDownloader`  parallel Range requests, resume, `.part` file, same as desktop
  - `HlsDownloader`   parse m3u8 (master -> pick best variant), fetch segments, AES-128 decrypt, write .ts,
                      then remux to .mp4 with MediaExtractor/MediaMuxer (no ffmpeg dependency)
  - `Manager`         queue, pause/resume/cancel, persistence (JSON file first, Room later)
- `service/`  foreground service (dataSync) so downloads survive leaving the app + progress notification
- `browser/`  WebView + `shouldInterceptRequest` sniffer: collects video/m3u8/mpd URLs with the exact
              request headers (Referer/Origin/Cookie/UA), page title for naming. Bottom sheet lists
              detected media with an editable file name -> Download.
- `ui/`       Compose: Downloads tab, Browser tab, share-target ("Share link to Spotter"), settings (folder).
- Saving: MediaStore (Downloads/Spotter) so no storage permission is needed on API 29+.

## Milestones
1. Scaffold + build (done, `./gradlew assembleDebug` works)
2. Engine: HTTP downloader + manager, test on emulator with a local server
3. Foreground service + Downloads UI + notifications
4. Browser tab with sniffer + naming dialog
5. HLS (+ AES-128) + remux to mp4
6. YouTube etc.: add `youtubedl-android` (bundles yt-dlp + python, ~60MB) as the last step

## Decisions to confirm
- minSdk 26 (Android 8+). Raise it if you only care about recent phones.
- App id `dev.ahmed.spotter`, name "Spotter". Change before publishing anywhere.
- Skip DASH (.mpd) at first; most sites that matter serve HLS or plain mp4.

## Build / run
    export ANDROID_HOME=~/Android/Sdk
    ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/debug/app-debug.apk
An emulator image (android-35 google_apis_playstore) is already installed.
