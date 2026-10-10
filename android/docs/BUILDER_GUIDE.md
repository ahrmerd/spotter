# Builder guide

## Working loop (per milestone)
1. Re-read the milestone and its acceptance list in `IMPLEMENTATION_PLAN.md`. Copy the acceptance items into your
   report file as a checklist *before* coding (`android/reports/Mx.md`, template: `android/reports/TEMPLATE.md`).
2. Write tests for the acceptance items first or alongside the code. A milestone without its tests is not done.
3. Build in small steps. After each step: `./gradlew assembleDebug` and the relevant tests. Never leave the build red
   at the end of a working session.
4. Fill in the report with real output. Mark each acceptance item PASS / FAIL / NOT RUN with evidence.
5. Stop. Wait for `Mx-audit.md`. Fix everything listed as blocker/major, update the report, ask for re-audit.

## Commands
    cd android
    export ANDROID_HOME=~/Android/Sdk
    ./gradlew assembleDebug                  # build
    ./gradlew testDebugUnitTest              # JVM tests (engine lives here)
    ./gradlew connectedDebugAndroidTest      # instrumented, needs a running emulator
    ~/Android/Sdk/platform-tools/adb devices
    ~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
    ~/Android/Sdk/platform-tools/adb logcat -s Spotter:V '*:E'

Emulator: one AVD from system image `android-35 google_apis_playstore` (create with Android Studio's Device Manager or
`~/Android/Sdk/cmdline-tools/.../avdmanager` if present). Host from the emulator = `10.0.2.2`.

## Decide vs ask
- **Decide yourself:** names, internal structure inside a package, which Compose component to use, small refactors.
- **Ask / flag in the report:** adding a dependency, changing a public behaviour in the plan, skipping an acceptance
  item, anything touching `server/` or `extension/`, any of the "open questions" in plan section 1.
- **Never decide alone:** dropping or weakening a non-negotiable (plan section 4).

## Order of work inside a milestone
Types and DB schema -> pure logic with unit tests -> wiring (service/UI) -> manual checks on the emulator -> report.

## When you are stuck
Write down what you tried, the exact error, and what you think the cause is. Do not loop on the same failing command
more than three times; change approach or put the question in the report.

## Honest reporting examples
- Good: "`./gradlew testDebugUnitTest` -> 23 tests, 0 failed (pasted below). Resume-after-kill test is NOT RUN: needs
  emulator, I could not start one (disk)."
- Bad: "All tests pass." (no output, no counts)
- Bad: relaxing an assertion so a failing test goes green without saying so.
