# M1 audit

**Verdict: PASS WITH FIXES.** The engine is well built and the builder's report is honest, but one blocker and two majors
must be fixed (and re-audited) before M2 starts. No redo is needed; the fixes are local.

Reproduced build/tests: yes. `./gradlew clean assembleDebug testDebugUnitTest --rerun-tasks`: build OK,
20 tests, 0 failures (Filename 6, M1Acceptance 7, PartPlanner 4, HeaderReplay 1, Http2Protocol 2), matching the report.

Auditor probes (not part of the build) are in `android/reports/audit-probes/`; copy them into `app/src/test/...` to run.

## Acceptance
| Item | Result | Evidence |
|---|---|---|
| 100 MB, 4 parts, SHA-256 equal | PASS | re-run; 4 parts asserted; hash equal |
| Kill + resume, hash equal, bytes not re-downloaded | PASS (weakly) | hash equal. But the "kill" is a graceful coroutine cancel, and the assertion allows ~384 KB of re-download against 256 KB persisted (see F4). Real process death NOT-VERIFIED (M2) |
| Random disconnects, correct hash | PASS | ranged server only. The no-Range case fails (F1) |
| ETag change on resume restarts, new hash | PASS | hash equals new body |
| Server without Range completes via single stream | PASS for a clean stream; **FAIL when the stream breaks** (F1) |
| 403 mid-download -> NEEDS_REFRESH, no storm | PASS | 5 requests |
| 10 queued, limit 3 | PASS | `peakRunning=3`, but the assertion only requires `>= 1` (F4) |

## Findings

### F1 [blocker] No-Range download silently corrupts when the connection drops and is retried
- Where: `engine/http/HttpDownloader.kt:297-302` (no `Range` header when `!record.ranged`), `:331-332` (writes at `work.current`), `:343-347` (IOException -> retry without resetting `work.current`).
- Problem: on retry the server restarts the body from byte 0, but the bytes are written at the old `work.current` offset. The download ends `DONE` with the wrong content. This breaks "resume correctness beats speed" (plan section 4) and the "no silent failure" rule.
- Reproduce: `AuditProbeTest.noRangeServerDropsMidStreamThenRetries` (rangeSupport=false, request 2 cut after 32 KiB). Result: `state=DONE`, SHA-256 differs from the source (`729bbd5c...` expected, `1ddf2de3...` actual).
- Fixed when: for a non-ranged download every retry resets `work.current = work.start` (and `downloaded` accordingly, truncating/overwriting from 0). Add a regression test that is the probe above, and one where the retry also resumes after pause/restart. The same hole must be checked for the "200 instead of 206" path.

### F2 [major] Pause / kill leaves the download stuck in RUNNING when the store honours cancellation (as Room does)
- Where: `engine/queue/DownloadManager.kt:175-186` (the `catch (CancellationException)` block calls `store.get`/`store.update` in a cancelled coroutine), `HttpDownloader.kt:277-281` and `:115-121` (`finally` persist and `sink.force()` in a cancelled coroutine).
- Problem: with a real Room store every suspend DAO call goes through `withContext`, which throws `CancellationException` immediately in a cancelled coroutine. So the state update to PAUSED never happens, and the final progress `persist()` is skipped too. The unit tests use `InMemoryDownloadStore`, whose `Mutex.withLock` does not throw, so this is invisible. `resume()` on a RUNNING item only calls `pump()` (which looks at QUEUED only), so the download stays dead.
- Reproduce: `AuditCancelProbeTest` wraps the in-memory store in a Room-like store (`withContext(Dispatchers.IO)` per call). Result: `state=RUNNING` two seconds after `pause()`. (This models Room; real Room on a device is NOT-VERIFIED, but the mechanism is standard.)
- Fixed when: cleanup that must run on cancellation is wrapped in `withContext(NonCancellable)` (state update, final persist, `force`). Add the Room-like wrapper store to the test suite and run the pause and shutdown tests against it. Also decide and document what `resume()` does for a RUNNING item with no live job (should re-queue it).

### F3 [major] Resume does not verify the partial file; a missing or short `.part` resumes into zeros
- Where: `HttpDownloader.kt:75-85` (`sinkFactory.open` then `setLength(size)`), `sink/PrivateFileSink.kt:12,28` (`RandomAccessFile(..., "rw")` creates the file if absent).
- Problem: if parts exist in the DB but the `.part` file was deleted or truncated (user cleaned the folder, storage issue), the sink creates a new sparse file and `setLength` fills it with zeros; downloading continues from the old offsets and the result is `DONE` with holes. Found by code reading; NOT reproduced by a test.
- Fixed when: on resume with persisted progress, the sink is opened in "must exist" mode and its length must equal `record.size` (or at least cover the persisted offsets); otherwise reset parts and restart cleanly. Add a test that deletes and truncates the `.part` between pause and resume.

### F4 [minor] Weak assertions in the acceptance tests
- `M1AcceptanceTest.kt` queue test: `peakRunning >= 1` proves nothing about the limit; assert `== 3`.
- Resume test: `extra < persisted / 2 + 256 * 1024` permits re-downloading more than the persisted amount; assert a tight bound (for example `extra <= 2 * connections * MAX_CHUNK` or compare the exact requested ranges via `server.recorded`: every resumed `Range` start must be >= the persisted offset of that part).
- The "kill" is a graceful cancel (disclosed in the report). Add a hard-stop simulation: stop the manager without letting `finally` persist (or restore the store to a snapshot taken at an arbitrary moment) and resume from that.

### F5 [minor] `maxTotalConnections` setting has no effect
- `DownloadManager.kt:43` builds the semaphore from `DEFAULT_MAX_CONNECTIONS` once; `setSettings` stores the value but never applies it.

### F6 [minor] Unsynchronised shared counter
- `HttpDownloader.kt:226,266`: `bytesSincePersist` is a plain `var` updated from several IO threads. Use `AtomicLong`.

### F7 [minor] Small state-handling gaps
- `DownloadManager.pause()` sets `stop[id]` even when no job exists, leaving stale entries (`:94`).
- Wi-Fi-only is only checked when starting jobs; running downloads are not paused when Wi-Fi drops (not in the M1 acceptance list; track for M2).
- Crash window between `sink.complete()` (rename to the final file) and the DB update: after a restart the record still has parts, the engine reopens `final.part` and `complete()` then refuses to overwrite the existing final file -> ERROR (`HttpDownloader.kt:103-111`, `PrivateFileSink.kt:39-41`). Make completion idempotent.

### F8 [minor] Process
- The milestone was not committed (`AGENTS.md`: one commit `Mx: summary` per milestone). All M1 files are untracked/modified in the working tree. `reports/STATUS.md` open-findings column was left empty.

### Advice for M2 (not findings)
- `MediaStoreSink` always inserts a new row, so it cannot resume across a process restart (the stored URI is never reopened). Design this before wiring the service. It was not exercised (instrumented tests not written); NOT-VERIFIED.
- Real Room is also not exercised at runtime; add instrumented tests for `RoomDownloadStore` (insert, update, parts replace in a transaction, observeAll) in M2.

## Checks that came out clean
- No empty `catch` in production code (the only ones are in `RangeTestServer`), no `GlobalScope`/`runBlocking`/`Thread.sleep` in `main`, no cookie/token logging, no unbounded body reads, no new exported components or permissions beyond the plan.
- Cleartext: base config denies it; allowed only for `10.0.2.2`/`localhost`/`127.0.0.1` from `src/debug`. Release config has no cleartext. Good.
- Dependencies match the plan plus declared test libs (`junit`, `coroutines-test`, `mockwebserver`, `okhttp-tls`); KSP/Room as allowed. `server/` and `extension/` untouched.
- Filename sanitising: path separators, NUL, leading dots, length cap, unique suffix all handled; Content-Disposition `../../x` stays inside the folder.
- Probes that PASSED: pause/resume spam (25 toggles, hash equal, 4.19 MB served for a 3 MB file = ~40% overhead; acceptable but note it), same URL twice -> `source.bin` and `source (1).bin`.

## Not verified
- Full disk, server lying about `Content-Length`, hostile filenames through the real HTTP path, anything on a device/emulator, `MediaStoreSink`, `RoomDownloadStore`, token-bucket and speed limit behaviour.

## Plan deviations noticed
All nine in the builder's report are declared and accepted (ServerSocket test server, simulated kill, in-memory store for JVM, test libs, debug cleartext config, 16-connection cap, 1 MiB steal minimum, request-count 403 fixture, deterministic disconnect fixture). No undeclared deviations found.

## To close M1
Fix F1, F2, F3; address F4 to F8 or justify; commit as `M1: ...` (and a second `M1 fixes: ...` commit for the audit fixes); update `STATUS.md`; request re-audit. I will re-run the probes and the full suite.

---
## Fix status (applied by the auditor at the owner's request, 2026-10-10)

| Finding | Status | What changed / evidence |
|---|---|---|
| F1 blocker | FIXED | `HttpDownloader.fetchPart` resets `work.current = work.start` on every attempt when the server has no Range support. Regression: `M1FixesTest.noRangeStreamDroppedMidwayStillProducesCorrectFile`, `noRangePauseThenResumeRestartsFromZeroWithCorrectHash` |
| F2 major | FIXED | Cancellation cleanup (state update in `DownloadManager`, final persist + `force()` in `runParts`) runs in `withContext(NonCancellable)`. `resume()` re-queues a stale RUNNING record with no job. Found while fixing: a `resume()` that arrives while a pause is still being processed was lost; now handled by `resumeAfterStop`. New `RoomLikeStore` test store; tests `pauseEndsPausedWhenStoreHonoursCancellation_thenResumeCompletes`, `staleRunningRecordWithNoJobCanBeResumed`, `pauseResumeSpamKeepsDataCorrect` |
| F3 major | FIXED | New `FileSink.length()`. On resume with progress, a missing/short `.part` throws `RestartDownloadException` and the file restarts from 0. Tests `resumeAfterPartialFileDeletedRestartsCleanly`, `resumeAfterPartialFileTruncatedRestartsCleanly`; both were confirmed to FAIL with the check disabled |
| F4 minor | FIXED | Queue test now asserts `peakRunning == 3`. Resume test asserts every resumed Range starts at a persisted part offset. New hard-stop test (`FreezableStore`, no final persist) |
| F5 minor | FIXED | `maxTotalConnections` is applied (swappable semaphore, loaded at start and on `setSettings`) |
| F6 minor | FIXED | `bytesSincePersist` is an `AtomicLong` |
| F7 minor | PARTLY FIXED | stale `stop` entries no longer created; completion order made crash-safe (parts persisted as complete before the rename; a finished file with a RUNNING record is finalised, test `finishedFileWithRunningRecordIsFinalisedNotFailed`). **Not done:** Wi-Fi-only is still only checked at job start; pausing running downloads when Wi-Fi drops is deferred to M2 (needs the service/network callbacks) |
| F8 minor | FIXED | M1 committed; STATUS updated |

Evidence: `./gradlew testDebugUnitTest` -> 30 tests, 0 failures (Filename 6, M1Acceptance 7, M1Fixes 10, PartPlanner 4, HeaderReplay 1, Http2Protocol 2). `assembleDebug` OK.
Caveats: Room itself still only runs on a device (`RoomLikeStore` models its cancellation behaviour); `MediaStoreSink` unverified (M2).
**The fixes were written by the auditor, not the builder, so they need an independent look: ask the builder to read the diff of commit "M1 audit fixes" and confirm, or have it re-audit in M2's first step.**
