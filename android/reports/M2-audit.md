# M2 audit

**Verdict: PASS WITH FIXES.** M2 is solid and most of it works on a real device (API 35 emulator), including things the builder could
not run. Two major findings (A1, A2) must be fixed before M2 is considered done; they can be fixed on the M3 branch as agreed.

Audited commit: `08c4c72` (branch `m2`) in the separate audit worktree. Auditor changed no source code.

## Reproduced
- `./gradlew assembleDebug testDebugUnitTest`: build OK, **43 tests, 0 failures** (matches the report).
- `./gradlew connectedDebugAndroidTest` on `Medium_Phone_API_35` (API 35): **10 tests, 0 failures, 0 skipped**
  (RoomDownloadStoreTest 5, MediaStoreSinkTest 5). The report said NOT RUN (emulator disk); they pass.

## Acceptance (on the emulator, real device behaviour)
Test rig: `reports/audit-probes/rangeserver.py` (Range-capable, throttled 400 MB file) on the host, app downloading from `http://10.0.2.2:8765/`.

| Item | Result | Evidence |
|---|---|---|
| Large download, Home, wait 5 min: still running, notification | **PASS** | Pressed Home, waited ~6 min. The 400 MB download kept running in the background and finished (`.pending-...` moved to `Download/Spotter/audit-big.bin` at 12:02:59). Output size 419430400 B; **MD5 of the file equals the expected pattern** (`8e77bab1...`). FGS `isForeground=true types=0x1` (dataSync) while running; notification `channel=spotter_downloads`, ongoing, 2 actions (Pause all / Cancel), importance LOW |
| Swipe from recents | **PASS (service stays alive)** | Removed the task from recents (task count 0). Same PID, FGS still foreground, download continued 57 MB -> 88 MB -> 138 MB. Path used: the service survives (`stopWithTask=false`), not WorkManager |
| Airplane mode 30 s and back, resume without a tap | **PASS** | 16.2 MB -> 18.7 MB (stalled) in airplane mode; after network returned with the app in the background: 43.5 MB and 5 new ranged requests at the persisted offsets (e.g. `bytes=4956160-104857599`), no user action |
| Rotate + kill activity, list/progress correct | **PASS** | `always_finish_activities=1`, landscape, Home, relaunch, back to portrait: list and progress consistent (152.9 -> 162.5 -> 167.6 MB), finished item still shown |
| Share-target `ACTION_SEND` | **PASS** | Intent with extra text `Check_this_http://10.0.2.2:8765/big.bin` opened the Add dialog prefilled with the extracted URL; nothing downloaded until Download was tapped |
| Name collision | **PASS** | second download of the same file became `audit-big (1).bin` |
| Reboot with a download in progress (interim I1) | **PASS** | `adb reboot`: no crash, no process started at boot (receiver disabled by default). Opening the app resumed from persisted offsets (ranged requests `bytes=47177728-`, `152051712-`, `256909312-`), progress continued 138 -> 249 MB, no corruption seen in the resumed MediaStore pending file. (The emulator showed a "System UI isn't responding" dialog under load after reboot (load average 11); an emulator artefact, not attributed to the app.) |
| Background FGS start on API 35 (interim I1) | **CONFIRMED, handled** | `ForegroundServiceStartNotAllowedException` appears in logcat at 12:03:46 and 12:05:49 and is caught and logged (no crash): see A2 |
| Instrumented Room + MediaStore tests | **PASS** | 10/10 on device |

Not verified (stated plainly): the `dataSync` `onTimeout` path (cannot be triggered cheaply; **NOT RUN**), notification action buttons (present, not tapped), `POST_NOTIFICATIONS` denial, clipboard suggestion, Wi-Fi-only on a real network change, API 26-28 `PrivateFileSink`, SAF/custom folder (M5).

## Findings

### A1 [major] Stale pooled connections make the next download fail and keep failing
- Where: `engine/net/HttpClients.kt` (`.retryOnConnectionFailure(false)`), `engine/http/HttpDownloader.kt:prepare` (the first `probe.probe(...)` has no retry/backoff; any `IOException` ends in ERROR).
- Observed on device: after the first download finished, I added a second download from the same host about 5 minutes later. It failed instantly at 12:05:08 with `SocketException: Software caused connection abort` / `unexpected end of stream on http://10.0.2.2:8765/...` (`EOFException: \n not found: limit=0`), shown to the user as "Error: Software caused connection abort - 0 B". The server received **no request** for it. Manual Retry failed again (12:06:39, same error) and only worked after another retry: each attempt consumed one dead keep-alive connection left in OkHttp's pool by the first download (4 parts + probe). The same server worked fine from the host with curl and from the emulator shell with `nc`.
- Why it matters: this is the normal case for a user who downloads twice from the same site a few minutes apart (servers and NATs close idle keep-alive connections). OkHttp's default retry exists exactly for this; the code turns it off and the probe has no retry of its own.
- Fixed when: the probe (and part requests) tolerate a stale connection, either by leaving `retryOnConnectionFailure` at its default, or by evicting the pool (`connectionPool.evictAll()`) and retrying the probe a few times with the same backoff as parts. Add a JVM test: the test server closes idle keep-alive sockets (or drops the first request on a reused connection) and a second download from the same server still completes without a manual retry. Keep the 403 "no retry storm" test passing.

### A2 [major] The WorkManager resume path cannot start the foreground service on Android 15, and nothing tells the user
- Observed: `ResumeWorker` ran in the background (12:03:46 after the first download had already finished, and again at 12:05:49) and `DownloadService.start` was refused by the system (`Background started FGS: Disallowed ... code:DENIED`, `am_wtf` in the system log). The app caught it and logged it, so there is no crash (this fixes interim item I1). But it also means: after a `dataSync` timeout, after the process is killed, or on any resume attempted from a worker, downloads simply stay QUEUED until the user opens the app. The builder already flagged this as a question; the device shows it is real.
- Related: the worker ran with nothing to do (the download had finished); a stale network-wait `ResumeWorker` should not fire, or should check for pending work before trying to start a service.
- Fixed when: (1) the app tells the user when downloads are waiting but cannot resume in the background (a notification "Downloads waiting, tap to resume" that opens the app, using a normal notification channel, no FGS needed); (2) the plan's open question is decided and recorded in the report: user-initiated data-transfer job (API 34+) or "pause and notify", with a short description of what was tested; (3) workers do not call `DownloadService.start` when there is no QUEUED/RUNNING item. Mark the `onTimeout` behaviour as tested if it can be triggered (document the steps), otherwise keep it NOT RUN.

### A3 [minor] Weakened M1 assertion
- `M1AcceptanceTest.hundredMegFourPartsHashMatches` changed from `parts.size == 4` to `>= 4`. Disclosed and explained (work stealing can add a part); accepted, but add a separate assertion that the **initial** plan is 4 parts (for example by checking the number of distinct initial Range starts) so the original acceptance item is still checked.

### A4 [minor] Raw error text and unfinished item display
- A failed item shows the raw exception text ("Software caused connection abort") and the bare URL as its title (the filename is unknown until the probe succeeds). Map common errors to short messages ("Connection lost, tap to retry") and show the URL host or last path segment until a name is known. Fits M5's error taxonomy; note only.

### A5 [minor] Process
- M2 was committed once at the end (`08c4c72`) although the workflow asks for commits at every green build. The report's evidence for the instrumented tests said NOT RUN; please update `M2.md` (or the M3 report) with the device results above.

## Looks good
- Engine changes from M1 are limited to what M2 needed; the M1 audit fixes (NonCancellable cleanup, resumeAfterStop, partial-file check) held up on a device (pause/resume across process death and a full reboot).
- Share-target and clipboard never download without confirmation; `ShareUrls` accepts only http(s).
- `BootReceiver` is disabled and not exported until the user opts in; FileProvider scope is narrowed to the downloads directory; no empty catches or secrets in logs in the new code.
- MediaStore resume works across a real reboot (reopening the pending row), and the final file hash is correct.

## To close M2
Fix A1 and A2 (on `m3`, in a commit named `M2 audit fixes: ...`), address A3 to A5 or justify, add the tests named above, paste the evidence into `M3.md` under "M2 fixes". The auditor will re-run the A1 scenario on the emulator (second download from the same host after an idle gap) and the A2 notification behaviour.
