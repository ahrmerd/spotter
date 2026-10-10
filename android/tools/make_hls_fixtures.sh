#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/test/resources/hls"
rm -rf "$OUT"
mkdir -p "$OUT"

encode() {
  local dest_dir="$1"
  shift
  mkdir -p "$dest_dir"
  ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "testsrc2=duration=2:size=160x120:rate=10" \
    -f lavfi -i "sine=frequency=440:duration=2" \
    -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level 3.0 -b:v 80k \
    -c:a aac -b:a 32k -ac 2 \
    "$@"
}

# Plain MPEG-TS VOD
encode "$OUT/plain" \
  -f hls -hls_time 1 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_filename "$OUT/plain/seg%d.ts" \
  "$OUT/plain/prog.m3u8"

# AES-128, IV omitted so the media-sequence number is the IV
mkdir -p "$OUT/aes"
printf '0123456789abcdef' > "$OUT/aes/enc.key"
printf 'enc.key\n%s\n' "$OUT/aes/enc.key" > "$OUT/aes/enc.keyinfo"
encode "$OUT/aes" \
  -f hls -hls_time 1 -hls_list_size 0 -hls_playlist_type vod \
  -hls_key_info_file "$OUT/aes/enc.keyinfo" \
  -hls_segment_filename "$OUT/aes/seg%d.ts" \
  "$OUT/aes/prog.m3u8"
rm -f "$OUT/aes/enc.keyinfo"
# ffmpeg writes IV=0x00..00 for sequence 0; drop it so the downloader must use the media-sequence IV.
sed -i 's/,IV=0x[0-9A-Fa-f]*//' "$OUT/aes/prog.m3u8"

# fMP4 with EXT-X-MAP
encode "$OUT/fmp4" \
  -f hls -hls_time 1 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_type fmp4 \
  -hls_fmp4_init_filename init.mp4 \
  -hls_segment_filename "$OUT/fmp4/seg%d.m4s" \
  "$OUT/fmp4/prog.m3u8"

# Master with a separate audio rendition
mkdir -p "$OUT/master/v" "$OUT/master/a"
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "testsrc2=duration=2:size=160x120:rate=10" \
  -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level 3.0 -b:v 80k -an \
  -f hls -hls_time 1 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_filename "$OUT/master/v/seg%d.ts" \
  "$OUT/master/v/prog.m3u8"
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "sine=frequency=440:duration=2" \
  -c:a aac -b:a 32k -ac 2 -vn \
  -f hls -hls_time 1 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_filename "$OUT/master/a/seg%d.ts" \
  "$OUT/master/a/prog.m3u8"
cat > "$OUT/master/master.m3u8" <<'EOF'
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,LANGUAGE="en",URI="a/prog.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=40000,RESOLUTION=160x120,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio"
v/prog.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=80000,RESOLUTION=160x120,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio"
v/prog.m3u8
EOF

# Segments named .jpg (copy of plain, rewritten playlist)
mkdir -p "$OUT/jpg"
cp -a "$OUT/plain/." "$OUT/jpg/"
for f in "$OUT/jpg"/seg*.ts; do
  mv "$f" "${f%.ts}.jpg"
done
sed -i 's/\.ts/.jpg/g' "$OUT/jpg/prog.m3u8"

# Junk header in front of the first segment
mkdir -p "$OUT/junk"
cp -a "$OUT/plain/." "$OUT/junk/"
first=$(ls "$OUT/junk"/seg*.ts | sort | head -n1)
{
  printf 'JUNKHEADER!!!!'
  cat "$first"
} > "$first.tmp"
mv "$first.tmp" "$first"

# 720p MPEG-TS with large keyframes for the instrumented MediaMuxer remux test (E1).
AT="$ROOT/app/src/androidTest/assets/hls"
mkdir -p "$AT"
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "testsrc2=duration=1:size=1280x720:rate=30" \
  -f lavfi -i "sine=frequency=440:duration=1" \
  -c:v libx264 -pix_fmt yuv420p -preset ultrafast -b:v 6M -g 15 \
  -c:a aac -b:a 64k -f mpegts \
  "$AT/big.ts"

echo "fixtures in $OUT"
find "$OUT" -type f | sort
wc -c $(find "$OUT" -type f | sort)
echo "androidTest $AT/big.ts $(wc -c < "$AT/big.ts") bytes"
