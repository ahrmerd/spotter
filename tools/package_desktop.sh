#!/usr/bin/env bash
# Builds the shareable desktop packages into dist/: the extension zips and the full desktop bundle.
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(python3 -c "import json;print(json.load(open('extension/manifest.json'))['version'])")
OUT=dist; rm -rf "$OUT" /tmp/spotter-pack; mkdir -p "$OUT" /tmp/spotter-pack/{chrome,firefox,bundle}
EXT="background.js popup.html popup.js adblock.js adblock-state.js rules"
for b in chrome firefox; do (cd extension && cp -r $EXT /tmp/spotter-pack/$b/); done
cp extension/manifest.json /tmp/spotter-pack/chrome/manifest.json
cp extension/manifest.firefox.json /tmp/spotter-pack/firefox/manifest.json
(cd /tmp/spotter-pack/chrome && zip -qr "$OLDPWD/$OUT/Spotter-extension-chrome-$V.zip" .)
(cd /tmp/spotter-pack/firefox && zip -qr "$OLDPWD/$OUT/Spotter-extension-firefox-$V.zip" .)
B=/tmp/spotter-pack/bundle/Spotter-desktop
mkdir -p "$B/extension" "$B/server"
(cd extension && cp -r manifest.json manifest.firefox.json $EXT "$B/extension/")
cp -r server/app.py server/engine.py server/ui "$B/server/"
cp desktop/* "$B/"; cp LICENSE "$B/"
(cd /tmp/spotter-pack/bundle && zip -qr "$OLDPWD/$OUT/Spotter-desktop-$V.zip" Spotter-desktop)
ls -la "$OUT"
