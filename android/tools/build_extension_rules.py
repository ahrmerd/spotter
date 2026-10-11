#!/usr/bin/env python3
"""Builds extension/rules/ads.json (Chrome declarativeNetRequest) from the EasyList files bundled in the Android app.

Only simple host rules (`||host^`, optionally `$third-party` and resource types) are converted: that is what DNR can
express cheaply. Chrome guarantees 30,000 static rules per extension. Media requests are never blocked.
"""
import json, re, sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
lists = [root / "android/app/src/main/assets/adblock" / n for n in ("easylist.txt", "easyprivacy.txt")]
LIMIT = 29000
TYPES = {"script", "image", "stylesheet", "subdocument", "xmlhttprequest", "ping", "font", "other"}
MAP = {"subdocument": "sub_frame", "xmlhttprequest": "xmlhttprequest", "stylesheet": "stylesheet",
       "script": "script", "image": "image", "ping": "ping", "font": "font", "other": "other"}
DEFAULT = ["script", "image", "stylesheet", "sub_frame", "xmlhttprequest", "ping", "font", "other"]
# Seen on animepahe.pw / the owner's phone on 2026-10-11 (not in EasyList; they rotate): blocked even as pages (pop-ups).
EXTRA_HOSTS = ["bongaropianyl.qpon", "crdema.com", "bcbet.ng", "livejasmin.com", "1xbet.ng", "21bet.ng", "ft.games",
               "x2tsa.com", "073m.com", "wuytg.com", "ganziv.com", "ayhal.com", "refpa32010.com", "qpon"]

rx = re.compile(r"^\|\|([a-z0-9.-]+\.[a-z0-9-]+)\^(\$.*)?$")
groups = {}  # (types, third) -> ordered hosts; one DNR rule per group, matching each host and its subdomains
count = 0
def add(host, types, third):
    global count
    if count >= LIMIT:
        return
    g = groups.setdefault((tuple(types), third), [])
    if host not in g:
        g.append(host)
        count += 1

for h in EXTRA_HOSTS:
    add(h, DEFAULT + ["main_frame"], False)
for path in lists:
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        m = rx.match(line.strip())
        if not m:
            continue
        host, opts = m.group(1), (m.group(2) or "")[1:]
        types, third, ok = [], False, True
        for o in filter(None, opts.split(",")):
            if o == "third-party": third = True
            elif o in TYPES: types.append(MAP[o])
            else: ok = False
        if ok:
            add(host, types or DEFAULT, third)
rules = []
for (types, third), hosts in groups.items():
    for i in range(0, len(hosts), 1000):  # keep each rule at a sane size
        cond = {"requestDomains": hosts[i:i + 1000], "resourceTypes": list(types)}
        if third:
            cond["domainType"] = "thirdParty"
        rules.append({"id": len(rules) + 1, "priority": 1, "action": {"type": "block"}, "condition": cond})
out = root / "extension/rules/ads.json"
out.write_text(json.dumps(rules, separators=(",", ":")))
print(f"{count} hosts in {len(rules)} rules -> {out} ({out.stat().st_size // 1024} KB)")
