# Coding guide

- Kotlin official style. Match the surrounding code: naming, comment density, idioms.
- Coroutines: structured concurrency only. No `GlobalScope`. Blocking I/O on `Dispatchers.IO`.
  Cancellation must be respected (`ensureActive()` in loops; don't swallow `CancellationException`).
- Errors: model them (sealed class or enum with a human message). No empty `catch`. Log with context and
  rethrow or convert to a visible state.
- Network: one shared `OkHttpClient`; per-download `newBuilder()` only for timeouts/interceptors. Always close
  response bodies (`use {}`). Never read an unbounded body into memory; stream with a fixed buffer (64 to 256 KB).
- Files: writes go through `FileSink` (positioned writes). Flush/`force` at part completion and on pause.
- Persistence: Room is the source of truth. Batch progress writes (every 1 to 2 s), but write immediately on state
  changes (pause, error, done).
- UI: state hoisted into ViewModels exposing `StateFlow`; composables are stateless where possible.
  UI refresh rate for progress <= 4/s.
- Security: no cookies/tokens in logs (redact); `WebView` hardened as in plan section 4; don't export components
  unless required; cleartext traffic only in a debug-only network security config.
- Comments explain *why* (a CDN quirk, a bug lesson). Don't narrate what the code obviously does.
- Keep functions small enough to test. The engine must be testable on the JVM without Android.
- No TODO without a milestone reference (`// TODO(M5): ...`).
