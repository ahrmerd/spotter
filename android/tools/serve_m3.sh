#!/usr/bin/env bash
# Serve M3 browser test pages (python http.server) and media (Range-capable server).
# Emulator reaches the host at 10.0.2.2.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PAGES="$ROOT/pages"
FIX="$ROOT/fixtures"
HOST_HINT="${SPOTTER_HOST:-10.0.2.2}"
PAGE_PORT="${PAGE_PORT:-8765}"
PLAYER_PORT="${PLAYER_PORT:-8766}"
MEDIA_PORT="${MEDIA_PORT:-8767}"

mkdir -p "$FIX/hls" "$PAGES"

if ! command -v ffmpeg >/dev/null; then
  echo "ffmpeg is required to generate fixtures" >&2
  exit 1
fi

if [[ ! -f "$FIX/clip.mp4" ]]; then
  echo "generating clip.mp4"
  ffmpeg -y -f lavfi -i testsrc=duration=2:size=160x120:rate=10 \
    -f lavfi -i sine=frequency=440:duration=2 \
    -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level 3.0 \
    -c:a aac -shortest "$FIX/clip.mp4"
fi
if [[ ! -f "$FIX/clip-frag.mp4" ]]; then
  echo "generating clip-frag.mp4"
  ffmpeg -y -i "$FIX/clip.mp4" -c copy -movflags frag_keyframe+empty_moov+default_base_moof \
    "$FIX/clip-frag.mp4"
fi
cp -f "$FIX/clip.mp4" "$FIX/gated.mp4"

if [[ ! -f "$FIX/hls/master.m3u8" ]]; then
  echo "generating HLS"
  ffmpeg -y -i "$FIX/clip.mp4" -c:v libx264 -c:a aac -f hls -hls_time 1 -hls_list_size 0 \
    -hls_playlist_type vod -master_pl_name master.m3u8 \
    -var_stream_map "v:0,a:0" "$FIX/hls/stream_%v.m3u8"
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"; kill 0' EXIT
cp -a "$PAGES/." "$WORKDIR/"
# Substitute hosts so the same files work on emulator (10.0.2.2) and host.
find "$WORKDIR" -type f \( -name '*.html' -o -name '*.js' \) -print0 |
  xargs -0 sed -i "s|MEDIAHOST|${HOST_HINT}:${MEDIA_PORT}|g;s|PLAYERHOST|${HOST_HINT}:${PLAYER_PORT}|g"

echo "pages  http://${HOST_HINT}:${PAGE_PORT}/"
echo "player http://${HOST_HINT}:${PLAYER_PORT}/player.html"
echo "media  http://${HOST_HINT}:${MEDIA_PORT}/clip.mp4"

python3 -m http.server "$PAGE_PORT" --bind 0.0.0.0 --directory "$WORKDIR" &
python3 -m http.server "$PLAYER_PORT" --bind 0.0.0.0 --directory "$WORKDIR" &
python3 "$ROOT/media_server.py" --port "$MEDIA_PORT" --root "$FIX" \
  --require-origin "http://${HOST_HINT}:${PLAYER_PORT}" \
  --require-origin "http://127.0.0.1:${PLAYER_PORT}" \
  --require-origin "http://localhost:${PLAYER_PORT}"
