#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
command -v python3 >/dev/null || { echo "Python 3 is not installed. Install it, then run this again."; exit 1; }
exec python3 server/app.py
