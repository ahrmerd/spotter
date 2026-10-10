# Spotter

Spotter: a simple download manager that spots videos on the pages you browse.

## Run the manager
    python3 server/app.py          # http://127.0.0.1:6800, saves to ~/Downloads/DM
Optional: `ffmpeg` (HLS .m3u8, already needed), `yt-dlp` (YouTube etc.).

## Install the extension
- Chrome/Edge: chrome://extensions → Developer mode → Load unpacked → `extension/`
- Firefox: about:debugging → This Firefox → Load Temporary Add-on → `extension/manifest.firefox.json`

Play a video on any page; the toolbar icon shows a count. Click it → Download.
