#!/usr/bin/env python3
"""Auditor tool: Range-capable, throttled file server. usage: rangeserver.py PORT SIZE_MB KBPS"""
import sys, re, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
PORT, SIZE, KBPS = int(sys.argv[1]), int(sys.argv[2]) * 1024 * 1024, int(sys.argv[3])
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): print(self.command, self.path, self.headers.get("Range"), flush=True)
    def do_GET(self):
        s, e, code = 0, SIZE - 1, 200
        m = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if m:
            s = int(m.group(1)); e = int(m.group(2)) if m.group(2) else SIZE - 1; e = min(e, SIZE - 1); code = 206
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", 'attachment; filename="audit-big.bin"')
        self.send_header("Accept-Ranges", "bytes"); self.send_header("ETag", '"audit-1"')
        self.send_header("Content-Length", str(e - s + 1))
        if code == 206: self.send_header("Content-Range", f"bytes {s}-{e}/{SIZE}")
        self.end_headers()
        pos, chunk = s, 16384
        try:
            while pos <= e:
                n = min(chunk, e - pos + 1); self.wfile.write(bytes([pos % 251]) * n); pos += n
                time.sleep(chunk / (KBPS * 1024))
        except (BrokenPipeError, ConnectionResetError): pass
ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
