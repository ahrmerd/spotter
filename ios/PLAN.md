# Spotter for iOS: plan (draft 1, 2026-10-11)

Goal: the same app on iPhone: download manager + browser that finds videos/streams, ad/pop-up blocking, YouTube,
search suggestions, same look. Built **on this Linux machine with xtool** (no Mac), installed on the owner's iPhone and
friends' iPhones.

## 1. What xtool is (verified from its docs, not tried yet)
- MIT-licensed, 6.9k stars, 56 open issues. Builds a **SwiftPM package** into an `.app`, signs it with your Apple ID and installs it over USB.
- Linux needs: **Swift 6.4 toolchain** (swift.org), **usbmuxd** (already installed here), **Xcode 27 `.xip`** downloaded by hand from developer.apple.com (Apple ID login; xtool extracts the iOS SDK from it), and the xtool AppImage.
- Project = `Package.swift` + `xtool.yml` (bundle ID, Info.plist overrides, resources, extensions). Commands: `xtool new`, `xtool dev` (build + install + run), `xtool devices`, `xtool install`.
- Supports app extensions (widgets, share extension, Safari extension). **ExtensionKit is not supported yet.**
- Free Apple ID: xtool prefixes the bundle ID (`XTL-1234.com...`); limits below apply.

## 2. Hard limits that shape the plan
| Limit | Effect |
|---|---|
| **Free Apple ID signing: the app expires after 7 days**, max 3 sideloaded apps, 10 new app IDs per week | Every install must be re-signed weekly (xtool can reinstall over USB; AltStore/SideStore can refresh over Wi-Fi). **Friends cannot just install an IPA from the website** without re-signing it themselves |
| Paid Apple Developer Program ($99/yr) | 1-year signing, TestFlight (90-day builds, up to 10,000 testers by link: **the best way to share with friends**), ad hoc up to 100 devices |
| No Play-Store-style open sideloading; EU alternative marketplaces only in the EU | Distribution = TestFlight, or each friend re-signs with AltStore/SideStore, or the owner signs with a paid account |
| **No foreground service**: iOS suspends apps in the background | Downloads continue only through a **background `URLSession`** (system keeps them going, relaunches the app on completion). Our own multi-connection/HLS segment engine cannot run in the background. See M2 |
| No `shouldInterceptRequest` in `WKWebView` | The Android sniffer design does not port. See M3 |
| No NewPipeExtractor (Java) | YouTube needs a Swift extractor (see M6) |
| Disk: this machine has ~7.7 GB free; Xcode xip is several GB and the SDK is extracted from it | **Check the free space before M0.** Probably needs clearing more space or putting it on an external disk |
| The Android code does not port: Kotlin/Compose/Room/WorkManager/OkHttp | iOS is a **rewrite in Swift** reusing the *design* (plan, state machine, algorithms, tests' ideas) and the same server/site |

## 3. Technology choices (proposed, owner can change)
- **UI:** SwiftUI (works with xtool; no Interface Builder, no Xcode previews). Same design tokens (deep purple top bar, status tabs, rows).
- **Web:** `WKWebView` in a `UIViewRepresentable`.
- **Storage:** SQLite through **GRDB** (SwiftPM, MIT) or raw `sqlite3`. **Not SwiftData/Core Data** (their model compiler needs Xcode).
- **Networking:** `URLSession` (HTTP/2). Resumable chunked downloads for the foreground; background `URLSession` for the long files.
- **Ad blocking:** `WKContentRuleList` (Apple's native content blocker). Convert EasyList/EasyPrivacy to its JSON with a script (limit about 150k rules per list; split into several lists). Cosmetic and pop-up rules via injected `WKUserScript` (reuse `adblock.js` from the extension).
- **Video detection:** injected `WKUserScript` at document start in all frames hooks `fetch`, `XMLHttpRequest`, `HTMLMediaElement.src`, `<source>`, MSE `MediaSource` and reports `.m3u8/.mp4/.mpd` URLs through `WKScriptMessageHandler`. Same idea as the Android JS bridge; it cannot see requests the page does not make from JS (native HLS playback of `<video src=x.m3u8>` is still visible as an element attribute).
- **HLS save:** download segments with `URLSession`, decrypt AES-128 (CryptoKit/CommonCrypto), then mux to MP4 with **AVFoundation** (`AVAssetWriter` for passthrough, no re-encode). `AVAssetDownloadTask` is not suitable (it stores an opaque offline asset, not a file).
- **Saving files:** app Documents folder (visible in the Files app with `UIFileSharingEnabled`), plus "Save to Photos" for video, and the document picker for a custom folder.
- **YouTube:** evaluate a Swift package (YouTubeKit or similar) in a spike; otherwise a small server-side resolver on our VPS (decision needed: privacy and abuse).
- **Usage ping:** the same anonymous daily ping to `spotter.hanscadi.cloud/api/ping.php` (abi = `ios`), same Privacy switch.

## 4. Milestones
- **I0 Spike (1-2 days, the gate):** install Swift + xtool, extract the SDK, build "Hello" and run it on the owner's iPhone. Then a 1-screen app with a `WKWebView` that loads a page, shows the detected media from the injected script and downloads one MP4 with a background `URLSession`. Measure disk use. **If the toolchain cannot be installed here, stop and choose: free disk / external disk / build on a rented Mac or CI runner.**
- **I1 Skeleton:** package layout, theme, navigation (Downloads home, globe → browser, Return), settings store, usage ping, Info.plist, icon. Unit tests with `swift test` on Linux for all pure logic.
- **I2 Download engine:** model + state machine (port from `engine/`), HTTP download with resume and chunks in the foreground, background `URLSession` handoff, queue, speed limit, notifications (local), free-space check, error messages. Port the Kotlin unit-test cases to XCTest.
- **I3 Browser + detection:** tabs, address bar, search engines + suggestions, history, bookmarks, injected sniffer, detected-media sheet, long-press link menu.
- **I4 HLS:** parser, AES-128, parallel segments, MP4 mux, quality picker. Port `HlsPlaylist` and its tests first (pure logic, runs on Linux).
- **I5 Ad blocking:** rule-list converter script, `WKContentRuleList`, pop-up/redirect/overlay script, per-site switch, counter.
- **I6 YouTube / resolved downloads** (depends on the spike).
- **I7 Polish + distribution:** TestFlight or re-sign guide, download page iOS section, README.

## 5. Testing without a Mac
- Pure Swift logic (parser, state machine, filters, name sanitising): `swift test` on Linux, runs on this machine.
- UI and WebView behaviour: **only on a real iPhone** (there is no iOS Simulator on Linux). Every UI check is manual, with screenshots from the owner, or I use `idevicescreenshot` (libimobiledevice) when the developer disk image is available. Expect slower feedback than on Android.

## 6. Questions for the owner (needed before I0)
1. **Do you have an iPhone, which iOS version, and is it connected to this PC by cable?** (xtool needs iOS 17+ for the default templates; confirm.)
2. **Apple account:** free Apple ID (7-day re-sign) or paid Developer Program ($99/yr, TestFlight for friends)? This decides how friends get the app.
3. **Disk:** about 7.7 GB free now. OK to free more space or use an external drive for Xcode/Swift (probably 15-25 GB needed; to be measured)?
4. **Xcode download:** you must download `Xcode 27.xip` yourself from developer.apple.com with your Apple ID (I cannot log in for you).
5. **Scope of the first iOS release:** downloads + browser + detection + ad blocking first, YouTube later? (Recommended.)
6. **YouTube on iOS:** Swift package, or a resolver on your VPS?
