# M3 audit

**Verdict: PASS WITH FIXES.** The browser, sniffer and detection flow work on a real device, including the hard cross-origin-iframe
case. But there are three major findings (B1 to B3) that must be fixed before M3 is closed; they can be fixed on the `m4` branch as agreed.

Audited commit: `e0ace37` (branch `m3`), merged into the audit worktree. Auditor changed no source code.

## Reproduced
- `./gradlew assembleDebug testDebugUnitTest`: build OK, **81 tests, 0 failures** (matches the report).
- `./gradlew connectedDebugAndroidTest` (API 35 emulator): **11 tests, 1 failure** (`Migration1to2Test.migratesAndCreatesBrowserTables`). The builder had marked it NOT RUN. See B1.

## Device acceptance (the builder's six items were all NOT RUN; I ran them)
Rig: the builder's `tools/serve_m3.sh` on alternative ports (18765/18766/18767; the builder's own servers were occupying 8765-8769), app installed on the emulator.

| Item | Result | Evidence |
|---|---|---|
| Plain `<video src>` shows the correct item | **PASS** | sheet: `http://10.0.2.2:18767/clip.mp4`, name prefilled `Plain video - Spotter.mp4` |
| HLS page shows the correct item | **PASS** (detection) | sheet: `http://10.0.2.2:18767/hls/master.m3u8`, "HLS/DASH stream", suggested name `HLS stream - Spotter.m3u8` (see B6) |
| Cross-origin iframe player needing its own Referer/Origin | **PASS** | the video inside an iframe on another origin was detected (`gated.mp4`); **Download produced a 27911-byte file, the same size as the clip**. The gate returns 403 without the iframe's Referer, so the captured headers were replayed |
| `blob:` MSE player | **PASS with caveat** | The shipped test page cannot run: its `fetch` from another origin fails ("fetch failed: TypeError: Failed to fetch") because `media_server.py` sends no CORS header (B4). With the same page served same-origin the sheet showed two entries: the `blob:` URL and the real `clip-frag.mp4` URL |
| Popup page: `window.open` on load blocked, on click allowed | **PASS** | after load: 1 tab; after tapping the button: a second tab `https://example.com/popup-on-click` opened |
| Editable name: custom name, extension appended, collisions | **PASS** | typed `Custom_name_1` -> saved `Custom_name_1.mp4` (27911 B in `Download/Spotter`); the `.mp4` was added automatically |
| Tab restore after an emulator restart | **PASS** | the Browser tab came back on the last page |
| Downloads list still works with the new schema | PASS | finished items shown in the Downloads tab |

Not verified: the Room 1->2 migration (B1), cookie persistence across process death, sniffer-cache restore after process death,
history and bookmarks screens, desktop-site toggle, `DownloadListener` routing, SSL error handling, the host blocklist beyond the two
popup cases, and the M2 audit fixes on a device (A1 second download after an idle gap, A2 "tap to resume" notification): **NOT RUN**.
JVM tests for both pass. I will re-run A1/A2 on the emulator during the M4 audit.

## Findings

### B1 [major] Room migration 1 -> 2 is unverified and its test fails on device
- `Migration1to2Test` fails: `FileNotFoundException: Cannot find the schema file in the assets folder ... dev.ahmed.spotter.engine.store.SpotterDatabase/1.json`. The exported schemas (`app/schemas/.../1.json`, `2.json`) are not wired into the `androidTest` assets, so `MigrationTestHelper` cannot run.
- Why it matters: a wrong migration silently wipes or breaks every user's download history and browser tabs on update. The migration has never been executed.
- Fixed when: the schemas directory is added to the `androidTest` assets (for example `sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")`), the test passes on the emulator with pasted output, and it also asserts that existing `downloads` and `parts` rows survive and the new tables exist. No `fallbackToDestructiveMigration`.

### B2 [major] Cookies and Authorization headers are stored in plaintext, and the app allows backup
- Captured request headers (Referer, Origin, **Cookie, Authorization**, User-Agent) are written to Room: `downloads.headers` and `sniffer_cache.headersJson` (`SnifferCache.kt`, `HeaderJson`). I pulled `spotter.db` with `run-as` and the stored rows contain the header JSON. The test sites set no cookies, so only User-Agent and Referer were visible; real sites will store cookie values the same way. `dumpsys package` shows `ALLOW_BACKUP`: `AndroidManifest.xml` has `android:allowBackup="true"`, so these rows are eligible for cloud and adb backups.
- Violates plan section 4 ("cookies and headers ... used only for the request they belong to") and desktop lesson 6.
- Fixed when: (1) `allowBackup="false"` (or `dataExtractionRules` / `fullBackupContent` excluding `spotter.db`); (2) `Cookie` and `Authorization` are not persisted: take cookies from `CookieManager` at download time, keep `Authorization` in memory only, and strip both when writing `sniffer_cache` and `downloads.headers` (a test asserts it); (3) sniffer cache rows are deleted when a tab closes. State what happens to a paused download after a restart that needed a cookie (it must re-read from `CookieManager`, or go to NEEDS_REFRESH).

### B3 [major] The address bar is unusable and the keyboard covers the detection button
- The URL field is about one sixth of the screen width (the toolbar icons take the rest): it shows only `.htm`, `html`, `ne.ht`, so the user cannot see or edit a URL. After a page loads the field keeps focus and the keyboard reappears, covering the "N media" button; it needed one tap to close the keyboard and a second to open the sheet. This is the core flow of the app.
- Fixed when: the address field gets its own full-width row (or the remaining toolbar width), does not keep focus after navigation, the keyboard closes on submit, and the detected-media button is reachable with one tap. Include a screenshot in the report.

### B4 [minor] The MSE test page cannot run as shipped
- `media_server.py` does not send `Access-Control-Allow-Origin` for ungated media, so `mse.html` (served from another port) fails to fetch. Add CORS headers for the ungated media so the blob acceptance item can run for the next person. The sniffer itself behaved correctly with a same-origin copy.

### B5 [minor] Detected-media sheet
- A `blob:` entry offers a Download button that can only fail with a snackbar. Show it disabled with a short reason ("stream is in-page, use the other entry"), and list the real URL entry first.
- The labels under each item (`stream` / `media` / `playlist` / `unknown`) are unhelpful: show the type (for example video/mp4), the size when known, and the host.

### B6 [minor] Playlist downloads (carried to M4)
- The suggested name for an HLS item ends in `.m3u8` and Download enqueues the playlist file itself. Disclosed in the report; M4 must route these to the HLS downloader and name the output `.mp4`. Do not let the user download a 300-byte `.m3u8` as "the video".

### B7 [minor] The JS bridge is reachable from every frame
- `SpotterSniffer.media(url, kind)` is added to the WebView, so any frame, including third-party ad iframes, can call it and add fake entries to the sheet. It is narrow and validated (http(s)/blob only), so the impact is a misleading list entry. Mitigate by showing the host of each entry and, where possible, ignoring entries from frames unrelated to the page.

### B8 [minor] Notes
- The emulator process died twice during this session while the browser played video (no error in its log; it stayed up after a clean restart). I cannot attribute this to the app, but if it reproduces on your machine, report it; it may indicate heavy memory use in the WebView video path. After each boot "System UI isn't responding" appeared under load.
- The M3 report is honest: every acceptance item is marked NOT RUN and nothing is claimed that was not run. Please paste the device results above (or your own) into the M3/M4 report.

## Looks good
- WebView hardening as specified: file and content access off, mixed content blocked, geolocation denied, one JS bridge method with validation; `shouldInterceptRequest` only observes (always returns null); the user-gesture rule for `window.open` works on a device.
- Header capture works end to end for a cross-origin iframe player (the case that cost the most time on desktop).
- Titles are cleaned and used as default names; collisions and missing extensions are handled by the existing engine rules.
- Browser tabs survive an emulator restart.
- The M2 fixes added on this branch (stale-connection retry, waiting notification) have JVM tests that pass.

## Fix status (updated by the auditor)
**IN PROGRESS: the auditor is fixing B1, B2 and B3 (and B4/B5 if cheap) on the `audit` branch, in the audit worktree.**
The builder must **not** fix these (avoids duplicate work and merge conflicts). Keep working on M4. When the fixes are done this file
will say so, with test output and device evidence; then `git merge audit` into `m4`. Files the auditor will touch:
`app/build.gradle.kts`, `AndroidManifest.xml`, `engine/store/HeaderJson.kt` and the store mappers, a new `CookieSource` hook in
`engine/net/` (used by `RangeProbe` and `HttpDownloader`), `browser/` + `ui/BrowserScreen.kt` (address bar and sheet),
`tools/media_server.py`, and new tests. **M4/HLS code must use the same `CookieSource` hook** for playlist, segment and key requests
instead of persisting cookies (details will be in the fix notes).

| Finding | Status |
|---|---|
| B1 migration test + verification | in progress |
| B2 cookies/Authorization persistence + allowBackup | in progress |
| B3 address bar / keyboard | in progress |
| B4 CORS on test media server | in progress |
| B5 sheet labels / blob button | in progress |
| B6 playlist download | left to M4 (builder) |
| B7 bridge from all frames | not planned; builder may mitigate |

## To close M3
Fix B1, B2, B3 (commit `M3 audit fixes: ...` on `m4`), address B4 to B7 or justify, and paste evidence under "M3 fixes" in `M4.md`. The auditor will re-run the migration test, check the database for cookies, and re-check the address bar and detection flow on the emulator.
