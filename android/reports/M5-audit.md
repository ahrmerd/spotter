# M5 audit

**Verdict: PASS WITH FIXES (one blocker).** Most of M5 works on a device and looks good: settings, persistence, theme, battery dialog,
free-space check, error messages, log viewer, and the M4 remux fix. But **choosing a custom save folder crashes the app, and keeps crashing
it on every launch** (F1). That must be fixed before M5 is closed. One M4 finding is only partly fixed (F2, F3).

Audited commit: `f546b8b` (branch `m5`, includes the M4 audit fixes `b270d02`), merged into the audit worktree.

## Reproduced
- `./gradlew assembleDebug testDebugUnitTest`: build OK, **127 tests, 0 failures** (matches the report).
- `./gradlew connectedDebugAndroidTest` (API 35 emulator): **14 tests, 0 failures**, including the new `MediaMuxerRemuxerTest.remuxesTsWithLargeKeyframesToMp4` and `RoomDownloadStoreTest.settingsSurviveReload`. (The builder had these NOT RUN.)

## Device checks (API 35 emulator, debug build of `f546b8b`)

| Check | Result | Evidence |
|---|---|---|
| Battery prompt: explains why, vendor note, shown once | **PASS** | first launch showed "Keep downloads alive" with the Xiaomi/Huawei/Honor/Oppo/Vivo/Samsung note and Not now / Allow; after "Not now" and a force-stop + relaunch it did not reappear |
| Free-space check before writing | **PASS** | a 50 GB file (server reports the size, sends no data) failed immediately with "Error: Not enough free space (need 48 GB, have 3 GB) . 0 B / 48.83 GB" and **Retry / Copy error / Remove / Rename** buttons; nothing was written |
| Settings screen: all planned rows | **PASS** | concurrent downloads (1-8 slider), connections per file (1-16), speed limit chips, Wi-Fi only, Resume on boot, HLS default quality (Best/1080/720/480/360), Appearance (System/Light/Dark), Notifications, Save folder, Battery, Logs (View / Export) |
| Theme and persistence across a process kill | **PASS** | chose Dark (applied immediately, clean dark palette), 1 MB/s and Wi-Fi only; `am force-stop`, relaunch: all three came back |
| Wi-Fi only blocks and releases | **PASS** | on the emulator's cellular network a new HLS download stayed "Queued"; turning Wi-Fi only off started it by itself within seconds, with no tap on the item (also covers the M2 "resume without a tap" behaviour) |
| Speed limit reset | PASS | chips reset to Unlimited |
| Log viewer and redaction | **PASS** | View logs shows `E download failed id=1 InsufficientSpaceException: ...`; `files/logs/spotter.log` is mode `-rw-------`; `grep -ci "cookie\|authorization\|SECRET"` over the log: 0 |
| **M4 E1: 720p stream becomes an MP4** | **PASS** | my 1280x720 H.264+AAC stream (key frames of 215 KB and 81 KB) now downloads as an MP4: ffprobe `h264 1280x720 + aac`, duration 16.09 s (source 16.0 s). Decoded audio is **bit-identical** to the source (MD5 match). Decoded video: the first 399 frames match the source frame for frame |
| M4 E2: name shown equals name on disk | **PARTLY** | `.ts` is no longer renamed `.m2ts` (the remux now works so I could not re-test the fallback), but see F2: the row shows `index.mp4` while the file on disk is `index (2).mp4` |
| M4 E4: decode check prints nothing | **FAIL** | see F3 |
| **Save folder (SAF) picker** | **FAIL** | see F1 |

## Findings

### F1 [blocker] Choosing a save folder crashes the app on every launch afterwards
- Observed: Settings > Choose folder opens the system picker (it correctly refuses the storage root). I picked **Movies**, tapped "Use this folder" and then **Allow**. The app died, and from then on it **crashes at start-up and whenever Settings opens**; the only way out is clearing the app's data (`pm clear`). Download requests made while the folder was set also crashed the app.
- Stack: `java.lang.UnsupportedOperationException: Unsupported Uri content://com.android.externalstorage.documents/tree/primary%3AMovies` at `SafSink$Companion.displayName(SafSink.kt:175)` called from `SettingsViewModel.refreshFolderLabel(SettingsViewModel.kt:114)`.
- Cause: `displayName` runs `ContentResolver.query` on the **tree** URI. A tree URI cannot be queried; it must first be turned into a document URI with `DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))` (the file already has a helper `treeDocumentUri` that is not used here). `UnsupportedOperationException` is not among the caught exceptions, and the call happens when the settings view model is created, so the saved URI makes every start fail.
- Fixed when: the label code converts the tree URI first, catches `RuntimeException` and falls back to the last path segment, and the settings view model can never take the app down because of a bad stored folder (a stored URI that cannot be read is cleared with a message). Add an instrumented test with a real tree URI (`DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Movies")` plus a granted permission, or a document provider fake) and check on the emulator: choose a folder, relaunch, open Settings, **download a file into the chosen folder** and check that it appears there with the right name and size. Also check the case where the permission is later revoked.

### F2 [major] The row can show a different name from the file on disk (E2 only partly fixed)
- Observed: download of `index.m3u8` completed; the Downloads row says `index.mp4`, but `Download/Spotter` contains `index (2).mp4` (`index.mp4` and `index (1).mp4` already existed from earlier runs, which MediaStore knew about and the app's database did not). MediaStore renamed the new file and the app kept its own name.
- Fixed when: after the sink is created the row's name is read back from the real display name (the report says `FileSink.outputName` does this, but the collision case does not use it), or the app chooses the unique name against MediaStore itself. Test: pre-create a file with the same name in MediaStore, then download; the row and the disk must match.

### F3 [minor] E4 is not fixed: repeated audio timestamps remain and the last video frame is lost
- `ffmpeg -v error -i out.mp4 -f null -` on the 720p result prints `Application provided invalid, non monotonically increasing dts to muxer in stream 1: 7168 >= 7168` (the report says duplicate timestamps are bumped by 1 microsecond; at 44.1 kHz that is smaller than one tick, so the duplicate remains).
- The decoded video has **399 frames; the source has 400**. The first 399 match exactly, so **the last video frame is dropped** by the remux.
- Fixed when: the decode check prints nothing and the frame counts are equal on this stream and on the 900-segment one.

### F4 [minor] A queued download does not say why it waits
- With Wi-Fi only on and no Wi-Fi, the row shows only "Queued . 0 B". Show "Waiting for Wi-Fi" (and "Waiting for network" when offline).

### F5 [minor] Settings polish
- The "512 KB/s" chip wraps onto two lines ("512 KB/" and "s"); use shorter labels or let the chip row wrap by width.
- Log timestamps are UTC (the log shows 09:36 while the device clock said 10:36); label them UTC or use local time.

## Not verified (stated plainly)
The "Copy error" clipboard text, "Export logs" share sheet, the real effect of the speed limit on throughput, Resume on boot, the Notifications switch effect, the SAF download path (blocked by F1), B7's ad-frame filter on a real ad iframe (unit tests only), the M2 A2 "tap to resume" notification, and the Android 15 `onTimeout` path.

## Looks good
- The settings design is clear and every value is stored in Room and survives process death.
- Error messages are short and human; the free-space check happens before any file is created.
- The battery dialog is helpful and not nagging.
- Wi-Fi-only gating works and the queue releases by itself.
- The M4 fixes for E1 (remux of real-size video) and E3/E5/E6 hold on a device; the 720p MP4 plays and its audio is exact.

## To close M5
Fix F1 first, then F2, F3 (and F4/F5 or justify); add the tests named above; rerun the picker flow on the emulator and paste the result. The auditor will repeat the folder flow, the name-collision check and the decode and frame-count checks.
