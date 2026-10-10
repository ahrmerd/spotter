# Spotter for Android: implementation plan

Audience: the AI (the "builder") that will implement this. A second AI (the "auditor") reviews every
milestone against this document before the next one starts. Read this whole file first.

Spotter = a simple download manager + a built-in browser that spots videos on the page, with one-tap download
and a rename-before-download step. A working desktop version (Python server + browser extension) already
exists in `../server` and `../extension`. Treat it as a reference implementation and a source of hard-won
lessons (section 8), not as code to port line by line.

## 1. Decisions

Settled:

| Topic | Decision |
|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3 |
| App id / name | `dev.ahmed.spotter` / "Spotter" |
| Browser engine | System WebView (Chromium). No bundled browser, no GeckoView |
| HTTP client | OkHttp 4.x (HTTP/2 is required, see 8.1) |
| Persistence | Room (SQLite) with KSP. Add the missing KSP plugin; Room is not yet in `build.gradle.kts` |
| Concurrency | Kotlin coroutines + Flow. No RxJava, no raw thread pools |
| Background | Foreground service (`dataSync`) + WorkManager for resume |
| File output | MediaStore `Downloads/Spotter` (no storage permission on API 29+) |
| Stream muxing | Android `MediaExtractor` / `MediaMuxer`. No ffmpeg bundled |
| DRM / live / DASH | Out of scope for v1 |

Not yet answered by the owner. Build with these defaults, keep each one behind a clean seam so it is cheap
to change, and list any you relied on in your milestone report:

| Open question | Default to assume |
|---|---|
| Publish on Google Play? | **No.** Distributed as APK. (Play bans YouTube downloaders; if the answer becomes "yes", milestone 7 is dropped.) |
| YouTube method | NewPipeExtractor, in milestone 7 only, behind an interface `SiteResolver` |
| minSdk | 26 (currently set). Do not add workarounds for < 26 |
| Test device | Emulator (`android-35 google_apis_playstore` is installed). Real-device checks are listed separately and marked MANUAL |
| Ad blocking | Popup blocking + a small built-in host blocklist only. No filter-list engine |

## 2. Architecture (package `dev.ahmed.spotter`)

```
engine/    pure Kotlin, no Android UI types. Unit-testable on the JVM
  net/       OkHttp client factory, header handling, Range probe
  http/      HttpDownloader: parallel parts, resume, work stealing
  hls/       playlist parser, HlsDownloader, AES-128, remux
  queue/     DownloadManager: queue, limits, state machine, retries
  store/     Room entities/DAOs: Download, Part, Setting
  sink/      FileSink interface (positioned writes). Impls: MediaStoreSink, PrivateFileSink
service/   DownloadService (foreground), notifications, WorkManager resume worker
browser/   WebView host, Sniffer (request + JS), PopupBlocker, tab model
ui/        Compose screens: Downloads, Browser, Detected-media sheet, Settings
```

Rules:
- `engine/` must not import `android.app`, `android.view`, or Compose. It may use `android.content`/`MediaStore`
  only inside `sink/`. This keeps it testable and keeps the audit simple.
- One state machine for a download: `QUEUED -> RUNNING -> (PAUSED | DONE | ERROR | NEEDS_REFRESH)`.
  Every transition goes through `DownloadManager`. The UI never mutates state directly.
- The database is the source of truth. The service, the UI and WorkManager all read it. In-memory progress is
  a throttled cache on top (UI updates at most ~4/s, DB writes batched every 1 to 2 s).

## 3. Milestones

Each milestone ends with a **report** to the auditor (section 6). Do not start the next milestone until the
auditor says PASS.

### M1: Engine core (plain HTTP files)
Build `HttpDownloader`, `DownloadManager`, Room schema, `FileSink`.
- Probe: `Range: bytes=0-0` -> size, range support, filename (`Content-Disposition`, URL, content type).
- Parallel parts: default 4, configurable 1 to 16, positioned writes into a pre-allocated file.
- Work stealing: a part that finishes early takes the upper half of the slowest remaining part.
- Resume: part offsets persisted; validate `ETag`/`Last-Modified` on resume, restart if changed.
- No Range support: single stream, resume restarts from 0 (state this honestly in the UI).
- Retries: exponential backoff, capped. 403/404/410 -> `NEEDS_REFRESH` (do **not** retry in a loop).
- Global concurrency: max simultaneous downloads (default 3) and a total connection cap.
- Optional speed limit (token bucket) and "Wi-Fi only".
- Filename: sanitize (no `/`, NUL, leading dots, length cap), add an extension when missing, never overwrite
  (`name (1).ext`).
- Tests (JVM, using a local test HTTP server that supports Range, `If-Range`, `ETag`, throttling and
  mid-stream disconnects): see acceptance below. Provide that server as `engine/src/test/...`
  (the Python `http.server` does **not** support Range; do not use it).

Acceptance:
- 100 MB file, 4 parts: SHA-256 equals the source.
- Kill the process mid-download, restart, resume: SHA-256 equals the source and bytes already written are
  not re-downloaded (assert by counting server bytes served).
- Server drops connections randomly: still completes, correct hash.
- Server returns different `ETag` on resume: restarts cleanly, correct hash.
- Server without Range: completes via single stream.
- 403 mid-download: ends in `NEEDS_REFRESH`, no retry storm (assert request count).
- 10 queued downloads with limit 3: never more than 3 running (assert).

### M2: Service, notifications, Downloads screen
- `DownloadService` as foreground service `dataSync`; starts when work exists, stops when idle.
- Notification: aggregate progress, Pause all / Cancel actions. Android 13+ `POST_NOTIFICATIONS` request flow.
- Handle Android 15 `dataSync` timeout (`onTimeout`): pause cleanly, persist, schedule a WorkManager resume.
- Resume on network change and (opt-in setting) on boot.
- Downloads screen: list, progress, speed, ETA, pause/resume/cancel/retry/remove, open, share, delete file,
  rename, filter by category, search. Add-by-URL dialog. Handles the Android share-target intent
  (`ACTION_SEND` text/plain) and clipboard suggestion.
- Compose state comes from Room `Flow`, not from the service object.

Acceptance (emulator, MANUAL + instrumented where possible):
- Start a large download, press Home, wait 5 min: still running, notification updating.
- Swipe the app from recents: download continues (service alive) or resumes via WorkManager. State which.
- Toggle airplane mode for 30 s and back: resumes without user action.
- Rotate the screen and kill the activity: list and progress are correct afterwards.

### M3: Browser tab + sniffer
- `WebView` per tab (multiple tabs), URL/search bar, back/forward/refresh, desktop-site toggle,
  persistent cookies (`CookieManager`), bookmarks and history (Room).
- Popup blocker: block `window.open` and new-window requests not triggered by a user gesture; block
  navigations to a small host blocklist. Never block the main-frame navigation the user typed.
- `Sniffer`, three layers (all three are required):
  1. `shouldInterceptRequest`: record URL + request headers (Referer, Origin, Cookie, User-Agent) for
     media-looking requests (extension or `m3u8/mpd/mp4/webm/...`), skipping segments (`.ts`, `.m4s`).
     **Do not return a response from it** (that breaks the page); only observe.
  2. Injected JS (`evaluateJavascript` at page finish and on DOM mutation): `<video>/<audio>` `src`,
     `currentSrc`, `<source>` children.
  3. Probe: for candidates without a known type, a `HEAD`/`Range 0-0` request to read type and size.
- Cookies: use `CookieManager.getCookie(url)`, not just the intercepted header (the intercepted request
  often lacks them).
- Detected-media bottom sheet: list of items (newest first, title, type, size when known), **an editable file
  name prefilled with the page title** (cleaned: strip site suffixes and notification counts like `(3)`),
  Download button per item. Same naming rule as the desktop extension.
- Page-level fallback entry for site URLs that need a resolver (wired in M7).
- WebView downloads (`DownloadListener`) are routed to Spotter.
- The sniffer list is per tab and cleared on navigation; keep it in memory plus a small persisted cache so a
  killed process does not lose it.

Acceptance:
- Local test pages (served from the host machine, reachable at `10.0.2.2` from the emulator) containing:
  a plain `<video src=x.mp4>`, an HLS player (hls.js) with a master playlist, a player in a **cross-origin
  iframe** that requires `Referer`/`Origin` of the iframe, and a `blob:` MSE player. Each case must show the
  correct detected item, and for the iframe case the download must succeed using the captured headers.
- Popup test page that fires `window.open` on load and on click: only the on-click one is allowed.
- The editable-name flow: custom name is honoured, extension appended when missing, collisions renamed.

### M4: HLS
- Parse master/media playlists (`#EXT-X-STREAM-INF`, `#EXT-X-MEDIA` audio renditions, `#EXT-X-KEY`,
  `#EXT-X-MAP`, `#EXT-X-BYTERANGE`, relative and absolute URLs, query strings).
- Quality picker dialog (resolution + bitrate) with "best" as the default.
- Segment downloads in parallel (default 8), ordered assembly, per-segment retry, resumable by segment index.
- AES-128: fetch key through the same header set, IV from the playlist or the media-sequence number.
  `SAMPLE-AES` and DRM keys -> clear "not supported" error, never a silent corrupt file.
- Segment payload sniffing, not extension trust: segments may be named `.jpg`/`.png`. Find MPEG-TS by the
  `0x47` sync byte pattern (every 188 bytes); if there is junk before it, skip the junk.
- Output: fMP4 (`#EXT-X-MAP`) -> concatenate init + fragments to `.mp4`. MPEG-TS -> remux to `.mp4`
  with `MediaMuxer` (video + audio, including a separate audio rendition). If a codec cannot go in MP4,
  keep `.ts` and tell the user why.
- Progress: by segments completed (bytes unknown up front). The UI must show a determinate bar, not
  "0 B" (a bug the desktop version had).
- VOD only. Live (`no #EXT-X-ENDLIST`) -> clear message.

Acceptance:
- Generated test streams (use ffmpeg on the dev machine to make fixtures; commit the generated fixtures,
  small): plain TS, AES-128 with IV from sequence, fMP4, master with separate audio, segments named `.jpg`,
  segments with a prepended junk header. Each downloads and the output **plays** (verify with `ffprobe` on the
  pulled file: correct duration +/- 1 s, one video and one audio stream).
- Interrupt and resume at segment 40 of 100: no duplicated or missing segments (compare against a straight
  download).
- Real-world check (MANUAL, owner machine): the owocdn/kwik HLS stream from the desktop notes in 8.1.

### M5: Settings, polish, robustness
- Settings: concurrent downloads, connections per file, speed limit, Wi-Fi only, resume on boot,
  default quality, notifications, theme (system/light/dark), custom save folder via SAF (optional, behind
  a flag if it proves hard).
- Battery-optimization prompt (explain, do not nag), per-vendor note.
- Storage checks: not enough free space -> clear error **before** pre-allocating.
- Error taxonomy with human messages (no raw stack traces in the UI), "copy error" button.
- Basic logging to a rotating file, viewable/exportable from Settings.
- Back-pressure and memory: never hold a whole segment or file in memory (stream with bounded buffers).

### M6: Release hardening
- R8/ProGuard on in release; verify HLS, OkHttp and Room still work minified.
- Signed release build instructions in `README`. No keystore committed.
- Lint clean at warning level that matters (no new errors; list suppressed warnings with reasons).
- Verify behaviour on Android 8, 10, 13, 14, 15 emulators (or state which could not be tested).

### M7 (optional, gated on the open questions): site resolvers
- `SiteResolver` interface: `canHandle(url)`, `resolve(url) -> List<MediaOption>` (video+audio streams,
  title, headers).
- First implementation: NewPipeExtractor for YouTube. Merge separate video/audio with `MediaMuxer`.
- yt-dlp via youtubedl-android is **not** started without an explicit go-ahead (size, update burden,
  JS-runtime requirement for YouTube is unverified on Android).
- If the owner decides to publish on Google Play, delete this milestone entirely.

## 4. Non-negotiables

- **Data safety:** never delete or overwrite a user's existing file. Never delete a finished file unless the
  user explicitly chose "delete file".
- **Resume correctness beats speed:** a resumed download must be byte-identical to a straight one. If there is
  any doubt (changed `ETag`, short read), restart that part or file.
- **No silent failure:** every error ends in a visible state with a reason. No swallowed exceptions
  (`catch (e: Exception) {}` is a review failure).
- **Privacy:** cookies and headers captured by the sniffer are used only for the request they belong to. Do not
  log full cookie values or tokens; redact in logs and in "copy error". Network traffic goes only to the
  site the user is downloading from. No analytics, no third-party SDKs not listed in this plan.
- **Security of the app itself:** `android:exported` only where needed; no `usesCleartextTraffic` blanket
  (allow cleartext only for the dev test hosts via a debug-only network security config); `WebView`:
  `setAllowFileAccess(false)`, no `addJavascriptInterface` of anything powerful (if you need a bridge for the
  sniffer, it exposes one narrow method taking strings and does validation).
- **Dependencies:** only those named in this plan plus KSP/Room/WorkManager/test libs. Ask before adding any
  other, and pin versions.
- **Do not touch** `../server` and `../extension`. If you find a bug there, report it, do not fix it.
- **Disk:** the dev machine has little free space (~8 GB). Do not create AVDs beyond one; clean build outputs
  you no longer need; test files must be generated, not downloaded in bulk.

## 5. Environment (verified)

- JDK 21, Android SDK at `~/Android/Sdk` (platforms 31, 33 to 36, build-tools 33 to 35, NDK, emulator,
  system image `android-35 google_apis_playstore`), Android Studio at `~/android-studio`.
- Build: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug`. Gradle 8.10.2 wrapper, AGP 8.7.3,
  Kotlin 2.0.21, Compose BOM 2024.12.01. Baseline build passes; keep it passing.
- `adb` is at `~/Android/Sdk/platform-tools/adb`. The emulator reaches the host at `10.0.2.2`.
- Shell is fish. Don't rely on bash-only syntax in committed scripts (use `#!/usr/bin/env bash` and keep
  scripts in `android/tools/`).
- ffmpeg is installed on the host (use it to generate HLS fixtures; it is not a runtime dependency).

## 6. Reporting protocol (builder -> auditor)

At the end of each milestone, write `android/reports/Mx.md` containing:
1. **What was built** (files and the public behaviour), in plain words.
2. **How to verify**: exact commands (build, unit tests, instrumented tests, adb steps) the auditor can run.
3. **Evidence**: real command output pasted (test summaries with counts, hashes, `ffprobe` output,
   request counts). "It works" without output does not count.
4. **Deviations** from this plan, each with the reason. Silent deviations are a FAIL.
5. **Known gaps / not tested**, stated plainly. Anything marked MANUAL that you could not run goes here.
6. **Assumptions** you made for the open questions in section 1.

Rules for evidence: tests that were not run are reported as "not run", never as passing. If a test fails and
you changed the test or the fixture to make it pass, say so and justify it.

## 7. Audit protocol (auditor)

For each milestone the auditor will:
1. Re-run the build and the stated verification commands themselves; compare against the report.
2. Read the diff against the acceptance list item by item; every item is PASS / FAIL / NOT-VERIFIED.
3. Review against the non-negotiables (section 4) and the pitfalls (section 8). Typical grep checks:
   empty `catch` blocks, `Thread.sleep` in coroutines, unbounded `readBytes()`/`readText()` on network bodies,
   `GlobalScope`, `runBlocking` on the main thread, hard-coded `10.0.2.2` or localhost in non-debug code,
   cookies/tokens in log statements, `exported="true"` additions.
4. Try to break it: pause/resume spam, process kill at random moments, rotate, airplane mode, full disk,
   duplicate downloads of the same URL, very long or hostile filenames (`../../x`, emoji, 400 chars),
   a server that lies about `Content-Length`.
5. Verdict: **PASS**, **PASS WITH FIXES** (listed, must be done before the next milestone), or **FAIL**
   (milestone is redone). The auditor does not write feature code; findings are written to
   `android/reports/Mx-audit.md` with file:line references and a severity (blocker / major / minor).

A milestone is done only when all acceptance items are PASS and no blocker or major finding is open.

## 8. Lessons from the desktop version (do not relearn these)

1. **HTTP/2 is mandatory for some CDNs.** A Cloudflare-fronted CDN returned 403 to every HTTP/1.1 client
   (ffmpeg, Python `urllib`, `curl --http1.1`) and 200 to HTTP/2 with identical headers. OkHttp negotiates
   HTTP/2 by default; do not force `Protocol.HTTP_1_1`, do not disable ALPN. Add a test that asserts the
   negotiated protocol for an HTTPS fixture.
2. **Use the headers the browser actually sent.** The Referer/Origin often belong to a cross-origin iframe
   player (e.g. `kwik.cx`), not the top-level page. The top-level URL as Referer gave 403. Capture request
   headers per media URL in the sniffer and replay them for every request of that download, including HLS
   playlists, segments and the key.
3. **Do not trust file extensions or content types for HLS segments.** Real streams used `.jpg` segment
   names, AES-128 encryption, and a separately fetched key. Probe by bytes.
4. **Playlist "size" is meaningless.** A `.m3u8` is a few hundred bytes. Never show its length as the video
   size (a popup showed "0.0 MB").
5. **HLS and DASH are not single files and YouTube is not a single URL.** YouTube serves chunked,
   separate video/audio, behind a player challenge. Direct URL sniffing is wrong for it; use a resolver
   (M7).
6. **Browser account cookies can break extractors.** Passing a logged-in YouTube `Cookie` header to yt-dlp
   made it fail with "Requested format is not available". Do not pass account cookies to resolvers
   unless there is a proven need, and never leak them to unrelated hosts.
7. **Feed pages preload many videos** (one tweet = one playlist). Sort newest first, show the page title and
   a time, and prefer the item detected closest to a `play` event when that signal exists.
8. **Page titles are dirty:** `(48) Title - YouTube`. Clean notification counts and site suffixes; sanitize
   characters illegal in filenames; always keep or add a sensible extension.
9. **Background workers die.** The Chrome MV3 service worker lost its detected list when idle; persist
   state that must survive process death.
10. **Local servers must authenticate their callers.** Not applicable on Android as designed (no local
    server). If you ever add one (e.g. for a desktop companion), reject requests whose `Origin`/`Host` you
    don't recognise (DNS-rebinding and drive-by POSTs from web pages).
11. **Make progress honest.** Unknown total size -> indeterminate bar with "x MB so far", not 0%.
12. **Python's `http.server` lacks Range support**; any test relying on it silently tests the non-range path.
    Your test server must implement Range properly.

## 9. Definition of done (v1 = M1 to M6)

- Plain files, HLS (incl. AES-128) download, pause, resume and survive process death, with byte-identical or
  playable output as defined above.
- Built-in browser detects videos on the test pages, replays the right headers, blocks popups, and lets
  the user rename before downloading.
- Foreground service keeps downloads alive; notification controls work; Android 15 timeout handled.
- All acceptance items PASS in the audit reports; no open blocker/major findings; build and unit tests green
  from a clean checkout.
