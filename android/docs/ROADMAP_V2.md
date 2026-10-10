# Roadmap v2: browser look, ad blocking, general downloads (owner feedback after testing)

Status: **plan only, nothing built.** Written by the auditor from the owner's notes and three screenshots of 1DM+.
Order of work: finish M1 to M6 first (owner decision), then these as M8 onward. M7 (YouTube) stays paused.

Owner's notes (from the Keep note):
1. Google search, maybe
2. A better browser look
3. Downloader for not just video
4. More aggressive ad blocking (go to animepahe.pw)
5. Open link in new tab

## 1. What the 1DM+ screenshots show (reference, not to be copied pixel for pixel)

| Screen | What 1DM+ does | What we learn |
|---|---|---|
| Downloads list | Purple top bar with menu, search, sort, globe (browser), and overflow. Three tabs with live counts: **ALL (1611) / DOWNLOADING (23) / FINISHED (1570)**. Dense rows: file-type icon, long name, "0% / 0.00B / 119.79MB", a one-line status, ETA, a three-dot menu per row. | Counts on tabs are very useful. Rows are compact, one tap target for actions. Colour carries state. |
| Failed rows | Orange rows: "Access forbidden by server. Please **refresh download link by using Refresh link in browser** or Refresh link option from menu of this download". Several AnimePahe items in this state. | Expired or token-bound links are the normal failure on this kind of site, even for 1DM. We need a first-class **Refresh link** action (reopen the page, re-detect, resume the same download). |
| Tabs screen | A list of numbered tabs with favicon, title and an X; a search icon; a bottom bar (power/exit, back, home, forward, +). | Tabs get their own screen. 249 tabs are open, so it must stay fast with many tabs. |
| Browser menu | New tab, Switch to Incognito, Return to 1DM+, **Remember tabs & Exit**, Exit browser, **Block Ads** (checkbox), **Disable Adblocker on this website** (checkbox), **Capture audio/video** (checkbox), **View Page Resources**, Open in App, Scan QR, Login using external browser, Import cookies, **Clone Tab**, **Grabber**, History, **Open tab for all links available in clipboard**. | The ad-block switch and a per-site exception live in the main menu. "View Page Resources" and "Grabber" are the answer to "not just video". |
| Address bar | A pill with a lock icon, the URL, and a **tab-count badge** (for example 249); one top row, no crowded icon strip. | Our current address row (7 icons plus a tab strip) is the opposite of this. |

## 2. Where Spotter is today (from the audits)
- Browser: tab strip, full-width URL field, a row of 7 icons (back, forward, reload, desktop, bookmark, bookmarks, history), a floating "N media" text button, DuckDuckGo for searches.
- Ad handling: popups without a user gesture are blocked and a small host blocklist is applied to navigations. **No request blocking at all**, no cosmetic hiding, no per-site switch.
- Downloads: category chips (All, Video, Audio, Image, Document) and a flat list; `NEEDS_REFRESH` exists but only offers Retry, which cannot fix an expired link.
- Detection covers media files and HLS only.
- Links: no long-press menu; `window.open` with a gesture opens a new tab.

## 3. Proposed work (each item is independent unless noted)

### A. Browser look (owner note 2): milestone M8
Goal: a calm, modern one-row browser that matches the rest of the app.
- **Top bar:** one row: `[tab-count badge] [lock + address pill (full width)] [overflow ⋮]`. Progress as a thin line under the pill. The address pill shows the host when not focused and the full URL when focused.
- **Bottom bar:** back, forward, home, new tab, tabs. It hides on scroll down and returns on scroll up (more room for the page).
- **Menu (⋮):** New tab, Incognito tab (separate cookie jar is not possible in one WebView profile: see risks), Desktop site, Bookmark, Bookmarks, History, **Block ads** (toggle), **Disable ad-block for this site**, **Page resources**, Clone tab, Share, Settings.
- **Tabs screen:** a list or a grid with title, favicon, close; "Close all"; "Close others"; search. Must stay usable with 200+ tabs (lazy list, WebViews destroyed when not visible and restored from Room).
- **Detected media button:** keep the floating chip, restyle it as a filled pill with a count, and pulse once when something new is found.
- **Empty tab page:** search box, bookmarks as tiles, recent history, "Paste link from clipboard".
- **Theme:** use the settings theme (system/light/dark) and the app's colour scheme; no purple-on-orange copying.
- Acceptance: screenshots of every state (light and dark), one-tap reach of the media list, no keyboard overlap, 200 tabs without jank, rotation keeps tabs.

### B. Open link in new tab (owner note 5): part of M8
- **Long-press on a link / image / media** shows a menu: Open in new tab, Open in background tab, Copy link, Download link, Share link, (image) Save image, Open image in new tab. Uses `WebView.HitTestResult` plus the long-press link URL.
- Make the **new-tab path** consistent for `target=_blank` with a gesture, links from other apps (`ACTION_VIEW` of http/https opens a new tab), and "Open tab for all links in the clipboard" (parses several URLs, opens one tab each).
- Snackbar "Opened in background" with a "Switch" action.
- Acceptance: instrumented UI test with a test page that has normal, `_blank`, image and download links; each menu item verified on the emulator.

### C. Search engine (owner note 1): small, part of M8
- Add a **search engine setting**: Google (default, as requested), DuckDuckGo, Bing, Brave, with custom URL template (`%s`).
- Omnibox suggestions are a later step (needs a network call per keystroke: privacy decision). First version: none.
- Acceptance: typing a non-URL goes to the chosen engine; the setting survives restart.

### D. Aggressive ad blocking (owner note 4): milestone M9
This is the biggest item and the one with real technical choices.
- **Where it plugs in:** `shouldInterceptRequest` can return an empty response for a blocked request. This changes a rule from M3 ("observe only, never return a response"). The new rule: **detection runs first, media requests are never blocked**, then the blocker may answer with an empty response for non-media ad and tracker requests.
- **Network rules:** bundle EasyList and EasyPrivacy (plus a "popups and annoyances" list), parsed at build or first start into a compact matcher: host rules `||example.com^`, path rules, the `$third-party`, `$script`, `$image`, `$subdocument`, `$popup` options, exceptions `@@`. Keep the matcher fast (hash-based on host and token; no regex on every request).
- **Cosmetic rules:** inject CSS to hide known ad containers (`##.ad-banner`) at page start; per-site rules for known sites; a "kill overlay" heuristic (a full-screen transparent element capturing clicks) for the click-jacking layers streaming sites use.
- **Navigation and popup hardening:** block cross-origin top-level redirects not caused by a gesture, `window.open` chains, `location` changes from ad frames, and `intent://`/app-store schemes; ask before leaving the typed site.
- **Controls:** global toggle in the menu and Settings, a **per-site exception** ("Disable ad-block for this site"), a counter ("14 blocked") on the page, and a one-tap "this page looks wrong: disable here".
- **Lists:** updated weekly in the background (WorkManager), downloaded over HTTPS from the list maintainers, with a built-in copy so it works offline. Licence note: EasyList is dual licensed (GPLv3 / CC BY-SA 3.0); attribute it in the app and keep the lists as data files.
- **Library or own code?** Options: (1) write a small matcher (about 800 lines, no dependency, enough for network + simple cosmetic rules; recommended first); (2) Brave's `adblock-rust` through JNI (best, handles full syntax; adds native code and a dependency, owner approval needed). Start with (1), design the interface so (2) can replace it.
- **Test on animepahe.pw:** measure before/after on the real site: count of blocked requests, popups prevented, overlays removed, page still plays video and **detection still works** (do not block the player, `.m3u8`, `.mp4`, key and segment requests). Test on the emulator if the site loads there; otherwise on the owner's phone.
- Risks: breakage of legitimate sites (hence the per-site switch and conservative defaults for non-streaming sites), the "aggressive" mode could be a separate Settings level (Standard / Aggressive).
- Acceptance: unit tests for the matcher against a list of sample rules and URLs; an instrumented test that a page with ad requests loads with them blocked and its video still detected; a manual check on animepahe.pw recorded with screenshots.

### E. Downloader for more than video (owner note 3): milestone M10
- **Detect any downloadable file** the browser meets: `DownloadListener` already catches attachments; add detection of links by extension and MIME (zip, rar, 7z, apk, pdf, epub, docx, xlsx, iso, torrent, audio, images) and of direct file URLs the page requests.
- **Page resources (like 1DM "View Page Resources" and "Grabber"):** a sheet listing every resource of the page grouped as Video, Audio, Images, Documents, Archives, Other, with size (from a `HEAD`), checkboxes, "select all", and a filename editor; download selected as a batch. Include images from `<img>`, `srcset`, CSS backgrounds, `<a href>` files.
- **Batch add:** paste many links (one per line) in the Add dialog; "Open tab for all links in clipboard" shares the parser.
- **Categories in Downloads:** keep type chips (Video, Audio, Image, Document, Archive, APK, Other); add the **ALL / DOWNLOADING / FINISHED tabs with counts** from the 1DM screenshot.
- **Open and install:** open files with the right app; APK files go through the system installer prompt (needs the "install unknown apps" permission flow; never auto-install).
- **Torrents/magnets:** out of scope for now (needs a separate engine); the list should say "not supported" rather than silently failing.
- Acceptance: a test page with a zip, a pdf, an image gallery and a document link; each is detected and downloaded with the right name and type; batch of 20 links works with the concurrency limit.

### F. Refresh link (from the 1DM failure screenshot): milestone M10 or M11
- Store the **page URL, title and the media URL's host** with every download created from the browser (without cookies).
- A failed or expired item (`NEEDS_REFRESH`, HTTP 401/403/404/410) gets a **Refresh link** action: opens the saved page in a tab; when the sniffer finds a media item matching the original (same name or same episode path), the app offers "Resume with the new link" and swaps the URL, keeping the parts already downloaded.
- For HLS, the same swap works when the new playlist has the same segment count and durations; otherwise offer a fresh start.
- Acceptance: a test server that expires tokens after N seconds; a download is interrupted, refreshed from the page, and completes with the correct hash.

### G. Downloads screen polish: M11
- **Tabs with counts** (All / Downloading / Finished), type chips under them, sort menu, search kept.
- **Compact rows**: file-type icon, name (2 lines max), one status line (percent, size, speed, ETA), three-dot menu for actions (pause, resume, cancel, retry, refresh link, rename, share, delete, open folder). State colours (running, paused, error, needs refresh) kept subtle, with an icon as well as colour for accessibility.
- **Selection mode**: long-press to select, then pause, resume, remove, delete files, share.
- **Empty states** with a short hint and a "Open browser" button.
- Acceptance: screenshots light/dark, 1000 rows scroll smoothly, selection actions tested.

## 4. Suggested order and size

| Order | Milestone | Content | Size | Needs from the owner |
|---|---|---|---|---|
| 1 | M8 | Browser look, tabs screen, long-press menu, open in new tab, search engine setting (A, B, C) | medium | choose the look (see questions) |
| 2 | M9 | Ad blocking engine, lists, per-site switch, hardening, animepahe test (D) | large | approve own matcher vs adblock-rust; test on animepahe.pw |
| 3 | M10 | Generic file detection, page resources, batch add, Refresh link (E, F) | large | none |
| 4 | M11 | Downloads screen polish (G) | medium | none |
| later | M7 | YouTube via NewPipeExtractor (paused) | medium | GPL decision, see the earlier note |

Dependencies: M8 first (the menu and the tab count hold the later toggles). M9 and M10 are independent after M8. F needs E's page-URL storage. G is best last because it displays everything the others add.

## 5. Constraints that apply to all of it
- No new library without the owner's approval; this plan asks about one (adblock-rust) and recommends not taking it first.
- Cookies and Authorization are never persisted (M3 audit B2). "Refresh link" must store the page URL only.
- Everything is tested on the emulator with pasted evidence, like earlier milestones; real-site checks (animepahe.pw) are manual and marked as such.
- The auditor reviews each milestone in the audit worktree on its own branch as before.

## 6. Questions for the owner
1. **Look:** do you want it close to 1DM (dense, purple top bar), or a cleaner Material 3 look following system colours? A screenshot of a browser you like (Chrome, Brave, Kiwi, Firefox) would settle it.
2. **Search:** Google as the default is fine; do you also want search suggestions while typing (sends keystrokes to the engine)?
3. **Ad blocking level:** one "Block ads" switch (as in 1DM) or a Standard / Aggressive choice? Are you comfortable with an automatic weekly list update from the internet?
4. **Library:** own small matcher first (recommended), or the heavier `adblock-rust`?
5. **Incognito:** do you want it? A real private mode with its own cookie jar needs the newer WebView profile API (Android 12+ devices with a recent WebView); on older ones it would only skip history.
6. **More than video:** is a list of page resources enough, or do you also want Spotter to appear in the system share sheet for any file or link (already done for links), and to take over browser downloads from other browsers?
7. **Torrents and magnets:** not needed for now?
