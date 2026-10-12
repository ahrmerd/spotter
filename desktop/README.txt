Spotter for desktop
===================

Two parts:
  1. The browser extension (folder "extension"): blocks ads and pop-ups, and spots videos on pages.
     This part works on its own.
  2. The download manager (folder "server"): downloads what the extension finds, including streams (.m3u8)
     and YouTube. Optional.

Install the extension
  Chrome / Edge / Brave:
    1. Open chrome://extensions   (Edge: edge://extensions)
    2. Turn on "Developer mode" (top right).
    3. Click "Load unpacked" and choose the "extension" folder from this zip. Keep that folder; do not delete it.
  Firefox:
    1. Open about:debugging, "This Firefox", "Load Temporary Add-on".
    2. Choose extension/manifest.firefox.json. (Firefox forgets it when it closes; repeat next time.)

Turn the ad blocker on or off: click the Spotter icon in the toolbar. "On" is for all sites,
"Off on this site only" is for the page you are on.

Download videos
  Play the video on the page, click the Spotter icon, then "Download".
  - Plain video files (.mp4 etc.) download through the browser even without the manager.
  - Streams (.m3u8) and YouTube need the manager (below).

Start the manager (optional)
  Windows: double-click start-spotter.bat      Linux / Mac: ./start-spotter.sh
  It needs Python 3 (python.org). For streams install ffmpeg; for YouTube install yt-dlp.
  Files are saved to the DM folder inside your Downloads folder. Leave the window open while it runs.
  Open http://127.0.0.1:6800 to see the downloads.

Mobile: the Android app is at https://spotter.hanscadi.cloud
Source code and licence (GPL-3.0): https://github.com/ahrmerd/spotter
