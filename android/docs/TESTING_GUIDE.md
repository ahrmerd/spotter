# Testing guide

## Test server (required for M1+)
Write a small JVM test server (e.g. OkHttp `MockWebServer` is fine for simple cases; use a custom `com.sun.net.httpserver`
or Ktor-less handler for streaming behaviour) that supports:
- `Range` / `206` / `Content-Range`, `Accept-Ranges`, `ETag`, `If-Range`
- configurable throttle (bytes/s), forced disconnect after N bytes, random disconnects
- a mode with no Range support, a mode that returns 403/410 after N requests
- counters: total bytes served, request count, per-request headers received (to assert that captured headers are replayed)
Never use Python `http.server` (it ignores Range, so tests silently cover the wrong path).

## Fixtures
- Generate with ffmpeg on the host (not at runtime in the app). Keep them tiny (a few seconds, low resolution).
- HLS set (script in `android/tools/make_hls_fixtures.sh`): plain TS; AES-128 (key file + IV from sequence);
  fMP4 with `#EXT-X-MAP`; master with separate audio rendition; segments renamed `.jpg`; segments with prepended junk bytes.
- Binary files: generate random data with a fixed seed so hashes are reproducible.

## What to assert
- Content: SHA-256 of output equals source (plain files); for streams, `ffprobe` duration within 1 s, expected
  stream count, and it decodes (`ffmpeg -v error -i out.mp4 -f null -` prints nothing).
- Behaviour: request counts (no retry storms), "bytes not re-downloaded" after resume, max concurrent downloads,
  negotiated protocol is `h2` for the HTTPS fixture.
- Robustness: kill/restart mid-download, flaky server, wrong `Content-Length`, filenames like `../../x`, `a\0b`, 400 chars, emoji.

## Levels
- **JVM unit tests** (`testDebugUnitTest`): parser, state machine, part splitting/work stealing, resume logic,
  filename sanitizing, header handling. Most tests live here.
- **Instrumented** (`connectedDebugAndroidTest`): MediaStore sink, Room migrations, service lifecycle.
- **MANUAL** (mark clearly): real phone, real sites, battery/OEM behaviour. List steps and results in the report.

## Browser tests (M3)
Serve test pages from the host (`python3 -m http.server` is fine *for pages*, not for Range media): a plain `<video>`,
hls.js player, cross-origin iframe player that needs iframe Referer/Origin, a blob/MSE player, and a popup page.
Reach them at `http://10.0.2.2:<port>/`.
