# Spotter

Spotter: a simple download manager that spots videos on the pages you browse.

## Run the manager
    python3 server/app.py          # http://127.0.0.1:6800, saves to ~/Downloads/DM
Optional: `ffmpeg` (HLS .m3u8, already needed), `yt-dlp` (YouTube etc.).

## Install the extension
- Chrome/Edge: chrome://extensions → Developer mode → Load unpacked → `extension/`
- Firefox: about:debugging → This Firefox → Load Temporary Add-on → `extension/manifest.firefox.json`

Play a video on any page; the toolbar icon shows a count. Click it → Download.

## Android app
`android/` is the Android app (Kotlin, Jetpack Compose): download manager with a built-in browser that detects videos and
HLS streams, YouTube support, ad and pop-up blocking.
Download the signed APK from https://spotter.hanscadi.cloud (one APK for every CPU, Android 8+).
Build: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug`.

The app and extension count installs with an anonymous daily ping (random install ID, app version, Android version, CPU
type); it can be switched off in Settings → Privacy. The server side is in `site/`.

## Licence
GPL-3.0 (see `LICENSE`): the Android app uses NewPipeExtractor (GPL-3.0). Filter lists in
`android/app/src/main/assets/adblock/` are EasyList and EasyPrivacy (CC BY-SA 3.0 / GPLv3).
