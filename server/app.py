#!/usr/bin/env python3
"""Local download manager: HTTP API + web UI on 127.0.0.1:6800."""
import json, os, re, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from engine import Manager

PORT = int(os.environ.get("DM_PORT", 6800))
DOWNLOAD_DIR = os.environ.get("DM_DIR", os.path.expanduser("~/Downloads/DM"))
STATE = os.path.join(os.path.expanduser("~/.local/share"), "dm-state.json")
UI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ui")
HOSTS = {f"127.0.0.1:{PORT}", f"localhost:{PORT}"}
mgr = Manager(DOWNLOAD_DIR, STATE)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _origin_ok(self):
        # Blocks DNS rebinding (Host) and drive-by requests from websites (Origin).
        if self.headers.get("Host") not in HOSTS:
            return False
        o = self.headers.get("Origin")
        return not o or o.startswith(("chrome-extension://", "moz-extension://")) or o.replace("http://", "") in HOSTS

    def _send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        o = self.headers.get("Origin")
        if o:
            self.send_header("Access-Control-Allow-Origin", o)
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        if not self._origin_ok():
            return self._send(403)
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", self.headers.get("Origin", ""))
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def do_GET(self):
        if not self._origin_ok():
            return self._send(403)
        if self.path == "/api/downloads":
            return self._send(200, mgr.list())
        if self.path == "/api/ping":
            return self._send(200, {"ok": True, "dir": DOWNLOAD_DIR})
        name = "index.html" if self.path in ("/", "") else self.path.lstrip("/")
        f = os.path.normpath(os.path.join(UI, name))
        if f.startswith(UI + os.sep) and os.path.isfile(f):
            ct = {"html": "text/html", "js": "text/javascript", "css": "text/css"}.get(f.rsplit(".", 1)[-1], "application/octet-stream")
            with open(f, "rb") as fh:
                return self._send(200, fh.read(), ct + "; charset=utf-8")
        self._send(404)

    def do_POST(self):
        if not self._origin_ok():
            return self._send(403)
        if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
            return self._send(415)
        try:
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            return self._send(400)
        if self.path == "/api/add":
            url = data.get("url", "")
            if not re.match(r"https?://", url):
                return self._send(400, {"error": "bad url"})
            it = mgr.add(url, data.get("headers"), data.get("filename"))
            return self._send(200, it.to_dict())
        m = re.fullmatch(r"/api/(\d+)/(pause|resume|remove|delete)", self.path)
        if m:
            id, act = int(m.group(1)), m.group(2)
            {"pause": mgr.pause, "resume": mgr.resume, "remove": mgr.remove,
             "delete": lambda i: mgr.remove(i, True)}[act](id)
            return self._send(200, {"ok": True})
        self._send(404)


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    srv.daemon_threads = True
    print(f"Spotter: http://127.0.0.1:{PORT}  (saving to {DOWNLOAD_DIR})")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        for i in list(mgr.items):
            mgr.pause(i)
        mgr.save()
