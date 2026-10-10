#!/usr/bin/env python3
"""Range-capable media server for M3 browser tests.

Serves files from a directory with HTTP/1.1 Range / 206. Optional Referer/Origin
gate for the cross-origin iframe case. Do not use Python's http.server for media.
"""
from __future__ import annotations

import argparse
import socket
import sys
import threading
from pathlib import Path


MIME = {
    ".mp4": "video/mp4",
    ".webm": "video/webm",
    ".m3u8": "application/vnd.apple.mpegurl",
    ".ts": "video/MP2T",
    ".m4s": "video/iso.segment",
    ".mpd": "application/dash+xml",
    ".key": "application/octet-stream",
}


def parse_range(header: str, size: int) -> tuple[int, int] | None:
    if not header.lower().startswith("bytes="):
        return None
    spec = header.split("=", 1)[1].strip()
    if "," in spec:
        spec = spec.split(",", 1)[0].strip()
    if spec.startswith("-"):
        length = int(spec[1:] or "0")
        start = max(size - length, 0)
        return start, size - 1
    start_s, _, end_s = spec.partition("-")
    start = int(start_s or "0")
    end = int(end_s) if end_s else size - 1
    if start >= size or start < 0 or end < start:
        return None
    return start, min(end, size - 1)


class Handler(threading.Thread):
    def __init__(self, conn: socket.socket, root: Path, require_origin: list[str], log: bool):
        super().__init__(daemon=True)
        self.conn = conn
        self.root = root
        self.require_origin = require_origin
        self.log = log

    def run(self) -> None:
        try:
            self.handle()
        except Exception as e:
            if self.log:
                print(f"error: {e}", file=sys.stderr)
        finally:
            try:
                self.conn.close()
            except OSError:
                pass

    def handle(self) -> None:
        f = self.conn.makefile("rwb", buffering=0)
        request = f.readline()
        if not request:
            return
        line = request.decode("iso-8859-1").strip()
        parts = line.split()
        if len(parts) < 2:
            self.reply(400, b"bad request")
            return
        method, raw_path = parts[0], parts[1]
        headers: dict[str, str] = {}
        while True:
            raw = f.readline()
            if raw in (b"\r\n", b"\n", b""):
                break
            k, _, v = raw.decode("iso-8859-1").partition(":")
            headers[k.strip().lower()] = v.strip()
        path = raw_path.split("?", 1)[0]
        if self.log:
            print(f"{method} {path} referer={headers.get('referer', '')!r} origin={headers.get('origin', '')!r}")
        if method not in ("GET", "HEAD"):
            self.reply(405, b"method not allowed")
            return
        if self.require_origin and not self.origin_ok(headers, path):
            self.reply(403, b"missing iframe referer/origin")
            return
        rel = path.lstrip("/")
        if not rel or rel.endswith("/"):
            self.reply(404, b"not found")
            return
        target = (self.root / rel).resolve()
        if not str(target).startswith(str(self.root.resolve())) or not target.is_file():
            self.reply(404, b"not found")
            return
        data = target.read_bytes()
        size = len(data)
        ctype = MIME.get(target.suffix.lower(), "application/octet-stream")
        rng = headers.get("range")
        if rng:
            parsed = parse_range(rng, size)
            if parsed is None:
                self.reply(416, b"range not satisfiable", extra=[f"Content-Range: bytes */{size}"])
                return
            start, end = parsed
            body = data[start : end + 1]
            extra = [
                f"Content-Range: bytes {start}-{end}/{size}",
                "Accept-Ranges: bytes",
                "Access-Control-Allow-Origin: *",
                "Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges",
                f"Content-Type: {ctype}",
                f"Content-Length: {len(body)}",
            ]
            self.reply(206, body if method == "GET" else b"", extra=extra, send_length=False)
            return
        extra = [
            "Accept-Ranges: bytes",
            "Access-Control-Allow-Origin: *",
            "Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges",
            f"Content-Type: {ctype}",
            f"Content-Length: {size}",
        ]
        self.reply(200, data if method == "GET" else b"", extra=extra, send_length=False)

    def origin_ok(self, headers: dict[str, str], path: str) -> bool:
        # Playlists and segments for HLS are fetched by the page origin (8765), not the iframe.
        # Only clip.mp4 on the iframe page is gated.
        # Only the iframe player's file is gated; plain/HLS/MSE use clip.mp4 freely.
        if not path.rstrip("/").endswith("gated.mp4"):
            return True
        referer = headers.get("referer", "")
        origin = headers.get("origin", "")
        for allowed in self.require_origin:
            if allowed and (referer.startswith(allowed) or origin.startswith(allowed)):
                return True
        return False

    def reply(
        self,
        status: int,
        body: bytes,
        extra: list[str] | None = None,
        send_length: bool = True,
    ) -> None:
        reason = {
            200: "OK",
            206: "Partial Content",
            400: "Bad Request",
            403: "Forbidden",
            404: "Not Found",
            405: "Method Not Allowed",
            416: "Range Not Satisfiable",
        }.get(status, "OK")
        headers = [f"HTTP/1.1 {status} {reason}", "Connection: close"]
        if extra:
            headers.extend(extra)
        elif send_length:
            headers.append(f"Content-Length: {len(body)}")
        payload = ("\r\n".join(headers) + "\r\n\r\n").encode("ascii") + body
        self.conn.sendall(payload)


def serve(host: str, port: int, root: Path, require_origin: list[str], log: bool) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sock.listen(16)
    print(f"media server on http://{host}:{port} root={root}")
    while True:
        conn, _ = sock.accept()
        Handler(conn, root, require_origin, log).start()


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8767)
    p.add_argument("--root", default=".")
    p.add_argument("--require-origin", action="append", default=[])
    p.add_argument("--quiet", action="store_true")
    args = p.parse_args()
    root = Path(args.root).resolve()
    serve(args.host, args.port, root, args.require_origin, log=not args.quiet)


if __name__ == "__main__":
    main()
