# M2 interim audit (read-only, while the builder is still working)

Scope: working tree at the time of review (nothing committed yet for M2). I did **not** run Gradle or touch the emulator, so
this is a code review only. Nothing below is a verdict; the formal `M2-audit.md` comes after the builder reports.
Items marked **VERIFY** are things I believe are wrong from the Android 12/15 rules but have not seen fail; the builder
should check them on the API 35 emulator and say what happened.

## Likely problems (fix before declaring M2 done)

### I1 [blocker?] VERIFY: foreground-service starts from the background will throw
- `SpotterApp.kt:onCreate` collects the store and calls `DownloadService.start(...)` whenever any item is QUEUED/RUNNING.
  `NetworkMonitor.onChange` and `BootReceiver` and `ResumeWorker` also call `DownloadService.start(...)` from background contexts.
- Since Android 12 `startForegroundService()` from the background throws `ForegroundServiceStartNotAllowedException`.
  On Android 15 a `BOOT_COMPLETED` receiver may not start a `dataSync` foreground service at all, and after the `dataSync`
  timeout the app may not start a new one until the user returns to the app.
- Consequences if confirmed: (a) after a reboot with downloads pending, `Application.onCreate` runs (the boot receiver launches the
  process even when "resume on boot" is off) and the collector in `appScope` throws with no handler -> **crash on every boot**;
  (b) the airplane-mode acceptance test would crash instead of resuming; (c) the `onTimeout` -> expedited worker ->
  `DownloadService.start` path cannot work on Android 15.
- Fixed when: every `start` is wrapped (catch `ForegroundServiceStartNotAllowedException` / `IllegalStateException`), has a fallback
  (keep state QUEUED and post a "tap to resume" notification, or use a user-initiated data-transfer job, which is what API 34+
  provides for long transfers), and `appScope` has a `CoroutineExceptionHandler` that logs instead of crashing.
  Boot resume must be opt-in **and** must not start a dataSync FGS from the receiver on API 35 (notify instead). Test: reboot the
  emulator with a QUEUED item (`adb reboot`) and show the app does not crash; run the airplane-mode test on API 35.

### I2 [major] The Android 15 `onTimeout` design is probably not workable as written
- `DownloadService.handleTimeout` parks downloads, then `ResumeScheduler.enqueueNow` runs `ResumeWorker`, which calls
  `DownloadService.start`. See I1: that restart is likely refused. The plan said "pause cleanly, persist, schedule a WorkManager
  resume": the resume must not rely on starting another `dataSync` service. Decide (and state in the report) between
  user-initiated data-transfer jobs (API 34+), or "pause + notify the user to reopen", and test `onTimeout` (it can be triggered
  with `adb shell am ...` / the `dumpsys` debug flags; document the exact steps used or mark NOT RUN).

### I3 [major] Service start/stop race
- `DownloadService.onCreate` stops itself (`stopIdle`) when the first emission shows no QUEUED/RUNNING item. If the service is
  started before the row is visible (or the first Room emission is empty), it stops and nothing restarts it until the next
  `SpotterApp` emission. Verify: add a download from the UI on a cold start and check the notification appears and the service
  keeps running. Add a test (instrumented or a Robolectric-free unit on the decision logic).

### I4 [minor] `BootReceiver` is `exported="true"`
- Not needed: system broadcasts reach non-exported receivers. Set `exported="false"`. As is, any app can send the
  action; it only starts the service when `resumeOnBoot` is on, but there is no reason to expose it.

### I5 [minor] FileProvider scope too wide
- `res/xml/file_paths.xml` has `<files-path name="files" path="." />`, which exposes the whole `filesDir`. Remove it; keep only the
  downloads directory (`external-files-path` `Download/`, which is `privateDir`). Check that `FileIntents.uriFor` never builds a URI
  for a file outside that directory.

### I6 [minor] NetworkMonitor churn
- `onCapabilitiesChanged` fires often (signal strength, validation). Each call reloads settings, runs `notifyNetworkChanged`, and
  enqueues/cancels WorkManager work. Debounce, and only react to changes in "online" / "unmetered".
  `AndroidNetworkPolicy.isOnline` checks only `NET_CAPABILITY_INTERNET`, not `VALIDATED` (captive portals count as online): note it.

### I7 [minor] ViewModel
- `DownloadsViewModel.enqueue` launches without the try/catch that the other actions use, so a failure crashes instead of showing a
  message. `lastBytes` map grows forever (never pruned). ETA/speed are computed from persisted progress (updated about once a
  second), so the numbers are coarse: acceptable, but say so.

## Looks fine so far
- Share-target and clipboard URLs only fill the add-URL dialog; nothing downloads without the user confirming. `ShareUrls` accepts
  only `http(s)`.
- Delete semantics are careful: `remove(id, deleteFile=false)` never deletes a finished file; partial files are dropped; `cancel`
  ignores DONE items.
- No new dependencies beyond WorkManager and androidx.test libs (allowed by the plan).
- M1 code changes are limited to what M2 needs (3-arg `FileSinkFactory.open`, new stop reasons, rename/cancel/remove).

## Not yet reviewable
`DownloadsScreen.kt` (428 lines), `DownloadNotifier.kt`, `AndroidSinkFactory.kt`, the instrumented tests
(`RoomDownloadStoreTest`, `MediaStoreSinkTest`) and `M2.md` evidence (the report is still a template). Re-review when the builder
stops. In particular I will check: MediaStore resume across a restart (`MediaStoreSinkTest`), that the instrumented tests actually
ran (pasted counts, not "written"), and the four plan acceptance items on the emulator.
