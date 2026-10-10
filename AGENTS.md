# AGENTS.md

Spotter: a download manager + video detector. Two parts exist:

- `server/` + `extension/`: desktop version (Python, stdlib only) and a Chrome/Firefox extension. **Finished. Do not modify.**
  Report bugs you find there; don't fix them.
- `android/`: the Android app. **This is the active work.**

## Roles
- **Builder** (you, if you are implementing): follow `android/IMPLEMENTATION_PLAN.md` milestone by milestone.
- **Auditor** (a separate AI/reviewer): verifies each milestone against the plan before the next one starts.
  The auditor does not write feature code. If you are the auditor, follow section 7 of the plan and `android/docs/AUDIT_GUIDE.md`.

## Two folders, two branches (builder and auditor work at the same time)
| Who | Folder | Branch |
|---|---|---|
| Builder | `/home/ahmed/projects/downloader` | `m2` (then `m3`, ... one branch per milestone) |
| Auditor | `/home/ahmed/projects/downloader-audit` | `audit` |

They are git worktrees of one repository: commits made in one folder are visible in the other.
- **Never edit files in the other person's folder.** Never run Gradle in the other folder.
- **Builder:** commit on your milestone branch. Commit often (at least at every green build), not only at the end. The auditor reviews commits, not your working tree.
  At the end of a milestone: `git merge audit` to pick up audit notes, make the final commit `Mx: <summary>`, then report.
- **Auditor:** keeps to `android/reports/`, `android/reports/audit-probes/` and docs. To review, `git merge m2` (or `git switch --detach <sha>` for a specific commit) in the audit folder, build and test there, and write `android/reports/Mx-audit.md`.
  The builder merges `audit` when it wants the notes. Audit-time code fixes go on `audit`, are listed in the audit file, and the builder merges them.
- When a milestone is PASS, merge its branch into `master` (owner or auditor), then the next milestone branches from `master`.
- **The emulator is shared** (one AVD, `emulator-5554`). Wrap anything that uses it in a lock so you don't collide:
  `flock /tmp/spotter-emulator.lock <command>`, e.g. `flock /tmp/spotter-emulator.lock ~/Android/Sdk/platform-tools/adb install -r app-debug.apk`.
  Don't start, kill, wipe or reconfigure the emulator without telling the owner. Check `adb devices` first: it is probably already running.
- `local.properties` is not in git. A new worktree needs its own copy (`sdk.dir=/home/ahmed/Android/Sdk`).

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
- Git: baseline commit is `4cac2b9`. Never rewrite or amend published history, never force-push, never commit ignored/build files.
  The final commit of a milestone has the message `Mx: <summary>`. The auditor diffs with `git diff master..m2` (or `4cac2b9..HEAD` for everything).

## Where things go
- Code: `android/app/src/main/java/dev/ahmed/spotter/...` (layout in plan section 2)
- Unit tests: `android/app/src/test/...`; instrumented: `android/app/src/androidTest/...`
- Test fixtures (small, generated): `android/app/src/test/resources/` and `android/tools/fixtures/`
- Reports: `android/reports/Mx.md` (builder), `android/reports/Mx-audit.md` (auditor)
