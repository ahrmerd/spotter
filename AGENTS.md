# AGENTS.md

Spotter: a download manager + video detector. Two parts exist:

- `server/` + `extension/`: desktop version (Python, stdlib only) and a Chrome/Firefox extension. **Finished. Do not modify.**
  Report bugs you find there; don't fix them.
- `android/`: the Android app. **This is the active work.**

## Roles
- **Builder** (you, if you are implementing): follow `android/IMPLEMENTATION_PLAN.md` milestone by milestone.
- **Auditor** (a separate AI/reviewer): verifies each milestone against the plan before the next one starts.
  The auditor does not write feature code. If you are the auditor, follow section 7 of the plan and `android/docs/AUDIT_GUIDE.md`.

## Read first, in this order
1. `android/IMPLEMENTATION_PLAN.md` (the source of truth: decisions, milestones, acceptance criteria, lessons)
2. `android/docs/BUILDER_GUIDE.md` (how to work, step by step)
3. `android/docs/CODING_GUIDE.md`, `android/docs/TESTING_GUIDE.md`
4. `android/PLAN.md` is an older overview, superseded by the implementation plan. If they disagree, the implementation plan wins.

## Ground rules (short version)
- One milestone at a time, in order. Stop at the end of each milestone and write the report; wait for the auditor's PASS.
- If the plan is ambiguous, wrong, or impossible, **stop and write it down in the report as a question.** Do not guess silently
  and do not quietly change the design.
- Never claim something works without pasted command output. "Not run" is an acceptable, honest answer; a false "passes" is not.
- Don't add dependencies that aren't in the plan. Don't touch `server/` or `extension/`.
- Never delete or overwrite user files; never commit secrets, keystores or `local.properties`.
- Keep the baseline build green: `cd android && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug`.
- The machine is low on disk (~8 GB). One emulator only; clean build outputs when you finish a milestone.
- Shell is fish. Put scripts in `android/tools/` with `#!/usr/bin/env bash`.
- Git: the baseline commit is `4cac2b9` on `master`. Make one commit per milestone when you hand it in (message `Mx: <summary>`),
  never rewrite or amend history, never force-push, never commit ignored/build files. The auditor diffs with `git diff 4cac2b9..HEAD`.

## Where things go
- Code: `android/app/src/main/java/dev/ahmed/spotter/...` (layout in plan section 2)
- Unit tests: `android/app/src/test/...`; instrumented: `android/app/src/androidTest/...`
- Test fixtures (small, generated): `android/app/src/test/resources/` and `android/tools/fixtures/`
- Reports: `android/reports/Mx.md` (builder), `android/reports/Mx-audit.md` (auditor)
