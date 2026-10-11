# Status

| Milestone | State | Date | Open findings |
|---|---|---|---|
| M0 scaffold (Compose shell, builds) | PASS (checked by owner's assistant: assembleDebug OK) | 2026-10-10 | 0 |
| M1 Engine core | PASS (after audit fixes F1-F8; F7 Wi-Fi-pause deferred to M2) | 2026-10-10 | 0 open (1 deferred) |
| M2 Service + Downloads UI | PASS (A1 stale-connection fix verified on device in the M4 audit; A2 waiting-notification and Android 15 onTimeout paths NOT RUN on a device) | 2026-10-11 | 1 not verified |
| M3 Browser tab + sniffer | PASS (B1-B5 fixed and verified on device; B6 closed in M4; B7 mitigated in M5, unit-tested only) | 2026-10-11 | 0 |
| M4 HLS | PASS (E1 remux verified on device with a 720p stream and a 10-minute stream; E2/E3/E5/E6 fixed; E4 folded into M5 F3, still open) | 2026-10-11 | F3 minor |
| M5 Settings + polish | PASS (F1 blocker, F2, F4, F5 fixed and verified on device; open minor: F3 remux audio sample grouping, `.ts.m2ts` for plain .ts files) | 2026-10-11 | 2 minor open |
| M6 Release hardening | PASS (R8, signing, lint clean, icon, README; other Android versions NOT RUN by owner decision) | 2026-10-11 | 0 |
| M7 Site resolvers (optional) | not started | | |
