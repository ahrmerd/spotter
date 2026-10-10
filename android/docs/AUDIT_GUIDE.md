# Audit guide

For each milestone, in this order:

1. **Reproduce.** Run the build and the exact commands from the builder's report. Different result than reported = finding.
2. **Checklist.** Go through the milestone's acceptance list: PASS / FAIL / NOT-VERIFIED, each with evidence. Anything the builder
   marked NOT RUN stays NOT-VERIFIED until someone runs it.
3. **Diff review.** Plan compliance (architecture rules, no forbidden deps, `server/`+`extension/` untouched), then the
   non-negotiables (plan section 4), then the lessons (plan section 8).
4. **Static checks** (examples):
   - `grep -rn "catch (.*) *{ *}" app/src` (empty catches)
   - `grep -rn "GlobalScope\|runBlocking\|Thread.sleep" app/src/main`
   - `grep -rn "Log\.\(d\|i\|v\|w\|e\)(.*\(cookie\|Cookie\|token\|Authorization\)" app/src/main`
   - `grep -rn "readBytes()\|readText()\|\.string()" app/src/main` on network bodies
   - `grep -n "exported" app/src/main/AndroidManifest.xml`; new permissions justified?
   - `grep -rn "10.0.2.2\|localhost\|127.0.0.1" app/src/main` (only allowed in debug sources)
   - dependency diff in `app/build.gradle.kts` vs the plan
5. **Break it.** Pause/resume spam, kill the process at random moments, rotate, airplane mode, full disk, same URL twice,
   hostile filenames, a server that lies about `Content-Length`, 10 downloads at once.
6. **Verdict** in `android/reports/Mx-audit.md` using the template: PASS, PASS WITH FIXES, or FAIL. Findings have
   severity (blocker / major / minor), file:line, what is wrong, how to reproduce, and what "fixed" looks like.

Principles: evidence over assertion; test the claim, not the explanation; be specific; don't fix code yourself; don't
approve a milestone with an open blocker/major finding; call out anything that looks like a test changed to hide a failure.

## Progress tracker
Keep `android/reports/STATUS.md` up to date: one row per milestone with state (not started / in progress / in audit /
fixes needed / PASS), date, and open findings count.
