"""Download engine: multi-connection HTTP, HLS via ffmpeg, page URLs via yt-dlp."""
import json, os, re, shutil, subprocess, sys, threading, time, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from email.message import Message

CONNECTIONS = 4
CHUNK = 256 * 1024
MIN_SPLIT = 1024 * 1024
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


def safe_name(name):
    name = urllib.parse.unquote(name).replace("/", "_").replace("\x00", "").strip(" .")
    return name[:200] or "download"


def unique_path(directory, name):
    base, ext = os.path.splitext(name)
    path, n = os.path.join(directory, name), 1
    while os.path.exists(path) or os.path.exists(path + ".part"):
        path = os.path.join(directory, f"{base} ({n}){ext}")
        n += 1
    return path


def kind_of(url):
    path = urllib.parse.urlparse(url).path.lower()
    if path.endswith((".m3u8", ".mpd")):
        return "hls"
    if re.search(r"(youtube\.com|youtu\.be|vimeo\.com|twitter\.com|x\.com|tiktok\.com|facebook\.com|instagram\.com|dailymotion\.com|twitch\.tv)", url):
        return "ytdlp"
    return "http"


def ts_offset(buf: bytes) -> int:
    """Index of the first MPEG-TS packet run (0x47 every 188 bytes, four in a row) in buf, or 0 if there is none."""
    for i in range(0, max(0, len(buf) - 564)):
        if buf[i] == 0x47 and buf[i + 188] == 0x47 and buf[i + 376] == 0x47 and buf[i + 564] == 0x47:
            return i
    return 0


class CurlProxy:
    """Local HTTP proxy that fetches through curl (HTTP/2, browser headers).

    Some CDNs (Cloudflare rules) 403 any HTTP/1.1 client, which is all ffmpeg and urllib speak.
    ffmpeg is pointed at this proxy; playlists are rewritten so every segment/key goes through it too.
    """
    def __init__(self):
        self.jobs = {}  # download id -> headers
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                try:
                    q = urllib.parse.urlparse(self.path)
                    id = int(q.path.split("/")[2])
                    url = urllib.parse.parse_qs(q.query)["u"][0]
                    headers = proxy.jobs[id]
                except (KeyError, ValueError, IndexError):
                    return self.send_error(404)
                cmd = ["curl", "-sS", "-f", "-L", "--http2", "--connect-timeout", "20", "--retry", "3"]
                for k, v in headers.items():
                    if k.lower() not in ("range", "host", "accept-encoding", "content-length"):
                        cmd += ["-H", f"{k}: {v}"]
                p = subprocess.Popen(cmd + [url], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    head = p.stdout.read(7)
                    if not head and p.wait():
                        self.send_error(502, p.stderr.read().decode(errors="replace").strip()[-150:])
                        return
                    if head == b"#EXTM3U":
                        body = self.rewrite(head + p.stdout.read(), url, id)
                        self.send_response(200)
                        self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                        return
                    self.send_response(200)
                    self.send_header("Connection", "close")
                    self.end_headers()
                    if head[:4] == b"\x89PNG" or head[:3] == b"\xff\xd8\xff" or head[:4] == b"GIF8":
                        # Disguised segment: a real image header in front of the MPEG-TS data. Drop the image part,
                        # or ffmpeg probes the segment as a picture and finds no video.
                        buf = head + p.stdout.read(65536 - len(head))
                        head = buf[ts_offset(buf):]
                    self.wfile.write(head)
                    while True:
                        buf = p.stdout.read(65536)
                        if not buf:
                            break
                        self.wfile.write(buf)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    p.kill()
                    p.wait()

            def rewrite(self, data, base, id):
                def px(u):
                    u = urllib.parse.urljoin(base, u)
                    # Some sites disguise MPEG-TS segments as .jpg/.png, which makes ffmpeg probe them as images.
                    ext = os.path.splitext(urllib.parse.urlparse(u).path)[1].lower()
                    ext = ext if ext in (".ts", ".m4s", ".mp4", ".m4a", ".m4v", ".aac", ".mp3", ".vtt", ".key", ".m3u8", ".cmfv", ".cmfa") else ".ts"
                    return f"http://127.0.0.1:{proxy.port}/p/{id}/s{ext}?u=" + urllib.parse.quote(u, safe="") + "&x=" + ext
                out = []
                for line in data.decode("utf-8", "replace").splitlines():
                    if line.startswith("#"):
                        line = re.sub(r'URI="([^"]+)"', lambda m: f'URI="{px(m.group(1))}"', line)
                    elif line.strip():
                        line = px(line.strip())
                    out.append(line)
                return ("\n".join(out) + "\n").encode()

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.srv.daemon_threads = True
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def url_for(self, id, url, headers):
        self.jobs[id] = headers
        return f"http://127.0.0.1:{self.port}/p/{id}/s.m3u8?u=" + urllib.parse.quote(url, safe="")


class Download:
    FIELDS = ("id", "url", "headers", "kind", "filename", "path", "status", "size", "downloaded", "error", "parts", "added", "ranged")

    def __init__(self, id, url, headers, directory, filename=None):
        self.id, self.url, self.headers = id, url, headers or {}
        self.kind = kind_of(url)
        self.directory = directory
        self.filename = filename
        self.path = None
        self.status = "queued"
        self.size = 0
        self.downloaded = 0
        self.error = ""
        self.parts = []  # [start, pos, end] inclusive-end
        self.ranged = False
        self.added = time.time()
        self.speed = 0
        self.cancel = threading.Event()
        self.lock = threading.Lock()
        self.proc = None

    def to_dict(self):
        d = {k: getattr(self, k) for k in self.FIELDS}
        d["speed"] = self.speed
        return d

    @classmethod
    def from_dict(cls, d, directory):
        o = cls(d["id"], d["url"], d.get("headers"), directory, d.get("filename"))
        for k in cls.FIELDS:
            if k in d:
                setattr(o, k, d[k])
        if o.status in ("downloading", "queued"):
            o.status = "paused"
        return o


class Manager:
    def __init__(self, directory, state_file):
        self.dir = directory
        self.state_file = state_file
        self.items = {}
        self.next_id = 1
        self.lock = threading.Lock()
        self.proxy = CurlProxy() if shutil.which("curl") else None
        os.makedirs(directory, exist_ok=True)
        self._load()
        threading.Thread(target=self._ticker, daemon=True).start()

    # ---- persistence
    def _load(self):
        try:
            with open(self.state_file) as f:
                for d in json.load(f):
                    it = Download.from_dict(d, self.dir)
                    self.items[it.id] = it
                    self.next_id = max(self.next_id, it.id + 1)
        except (OSError, ValueError):
            pass

    def save(self):
        with self.lock:
            data = [i.to_dict() for i in self.items.values()]
        tmp = self.state_file + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.replace(tmp, self.state_file)

    def _ticker(self):
        last = {}
        while True:
            time.sleep(1)
            for it in list(self.items.values()):
                if it.status == "downloading":
                    it.speed = max(0, it.downloaded - last.get(it.id, it.downloaded))
                else:
                    it.speed = 0
                last[it.id] = it.downloaded
            self.save()

    # ---- API
    def add(self, url, headers=None, filename=None):
        with self.lock:
            id = self.next_id
            self.next_id += 1
            it = Download(id, url, headers, self.dir, filename and safe_name(filename))
            self.items[id] = it
        self.start(it)
        return it

    def start(self, it):
        if it.status in ("downloading", "done"):
            return
        it.cancel.clear()
        it.status, it.error = "downloading", ""
        threading.Thread(target=self._run, args=(it,), daemon=True).start()

    def pause(self, id):
        it = self.items.get(id)
        if it and it.status == "downloading":
            it.cancel.set()
            it.status = "paused"
            if it.proc:
                it.proc.terminate()

    def resume(self, id):
        it = self.items.get(id)
        if it and it.status in ("paused", "error"):
            self.start(it)

    def remove(self, id, delete_file=False):
        it = self.items.pop(id, None)
        if not it:
            return
        it.cancel.set()
        if it.proc:
            it.proc.terminate()
        if it.path:
            for p in ((it.path, it.path + ".part") if delete_file else (it.path + ".part",)):
                if os.path.exists(p) and (delete_file or it.status != "done"):
                    try:
                        os.remove(p)
                    except OSError:
                        pass

    def list(self):
        return [i.to_dict() for i in sorted(self.items.values(), key=lambda i: -i.id)]

    # ---- workers
    def _run(self, it):
        try:
            {"http": self._http, "hls": self._hls, "ytdlp": self._ytdlp}[it.kind](it)
        except Exception as e:  # noqa
            if not it.cancel.is_set():
                it.status, it.error = "error", str(e)[:300]
        self.save()

    def _req(self, it, extra=None, method="GET"):
        h = {"User-Agent": UA, "Accept": "*/*"}
        h.update({k: v for k, v in it.headers.items() if k.lower() not in ("range", "host", "content-length")})
        h.update(extra or {})
        return urllib.request.urlopen(urllib.request.Request(it.url, headers=h, method=method), timeout=30)

    def _http(self, it):
        if not it.parts:
            # probe: size, range support, filename
            r = self._req(it, {"Range": "bytes=0-0"})
            cr = r.headers.get("Content-Range")
            ranged = r.status == 206 and cr
            size = int(cr.split("/")[-1]) if ranged and cr.split("/")[-1].isdigit() else int(r.headers.get("Content-Length") or 0)
            if not it.filename:
                it.filename = self._name_from(r, it.url)
            elif not os.path.splitext(it.filename)[1] or len(os.path.splitext(it.filename)[1]) > 6:
                it.filename += os.path.splitext(self._name_from(r, it.url))[1]
            it.path = unique_path(self.dir, it.filename)
            it.size, it.ranged = size, bool(ranged)
            r.close()
            with open(it.path + ".part", "wb") as f:
                if size:
                    f.truncate(size)
            if ranged and size >= MIN_SPLIT:
                step = size // CONNECTIONS
                it.parts = [[i * step, i * step, size - 1 if i == CONNECTIONS - 1 else (i + 1) * step - 1] for i in range(CONNECTIONS)]
            else:
                it.parts = [[0, 0, size - 1 if size else -1]]
        if not it.ranged:
            for p in it.parts:
                p[1] = p[0]
        fd = os.open(it.path + ".part", os.O_WRONLY | os.O_CREAT)
        errors = []

        def worker(p):
            try:
                while not it.cancel.is_set() and (p[2] < 0 or p[1] <= p[2]):
                    extra = {"Range": f"bytes={p[1]}-{p[2]}"} if it.ranged else {}
                    try:
                        r = self._req(it, extra)
                    except Exception:
                        time.sleep(2)
                        if it.cancel.is_set():
                            return
                        raise
                    while not it.cancel.is_set():
                        buf = r.read(CHUNK)
                        if not buf:
                            break
                        if p[2] >= 0:
                            buf = buf[: p[2] - p[1] + 1]
                        os.pwrite(fd, buf, p[1])
                        p[1] += len(buf)
                        with it.lock:
                            it.downloaded += len(buf)
                        if p[2] >= 0 and p[1] > p[2]:
                            break
                    r.close()
                    if p[2] < 0:
                        break
                    if p[1] <= p[2] and not it.cancel.is_set():
                        raise IOError("connection closed early")
            except Exception as e:  # noqa
                errors.append(e)
                it.cancel.set()

        it.downloaded = sum(p[1] - p[0] for p in it.parts)
        ts = [threading.Thread(target=worker, args=(p,), daemon=True) for p in it.parts]
        [t.start() for t in ts]
        [t.join() for t in ts]
        os.close(fd)
        if errors:
            it.status, it.error = "error", str(errors[0])[:300]
            it.cancel.clear()
        elif it.status == "downloading":
            os.replace(it.path + ".part", it.path)
            it.status = "done"
            it.size = it.size or it.downloaded

    @staticmethod
    def _name_from(resp, url):
        cd = resp.headers.get("Content-Disposition", "")
        m = re.search(r"filename\*=(?:UTF-8'')?([^;]+)", cd, re.I) or re.search(r'filename="?([^";]+)"?', cd, re.I)
        name = m.group(1) if m else os.path.basename(urllib.parse.urlparse(url).path)
        name = safe_name(name)
        if "." not in name:
            ext = {"video/mp4": ".mp4", "video/webm": ".webm", "audio/mpeg": ".mp3"}.get(resp.headers.get_content_type(), "")
            name += ext
        return name

    def _hls(self, it):
        if not shutil.which("ffmpeg"):
            raise RuntimeError("ffmpeg not installed")
        if not it.path:
            base = it.filename or os.path.splitext(os.path.basename(urllib.parse.urlparse(it.url).path))[0] or "video"
            if base.lower().endswith((".mp4", ".m3u8", ".mpd", ".ts")):
                base = base.rsplit(".", 1)[0]
            it.path = unique_path(self.dir, safe_name(base) + ".mp4")
        it.filename = os.path.basename(it.path)
        ua = next((v for k, v in it.headers.items() if k.lower() == "user-agent"), UA)
        if self.proxy:
            src, extra = self.proxy.url_for(it.id, it.url, it.headers), ["-allowed_extensions", "ALL"]
        else:
            hdr = "".join(f"{k}: {v}\r\n" for k, v in it.headers.items() if k.lower() not in ("range", "host", "user-agent"))
            src, extra = it.url, ["-user_agent", ua, "-headers", hdr]
        cmd = ["ffmpeg", "-y", "-loglevel", "error", *extra, "-i", src,
               "-c", "copy", "-bsf:a", "aac_adtstoasc", "-f", "mp4", it.path + ".part"]
        it.proc = subprocess.Popen(cmd, stderr=subprocess.PIPE, text=True)
        while it.proc.poll() is None:
            try:
                it.downloaded = os.path.getsize(it.path + ".part")
            except OSError:
                pass
            time.sleep(0.5)
        err = it.proc.stderr.read()
        rc, it.proc = it.proc.returncode, None
        if it.cancel.is_set():
            return
        if rc:
            raise RuntimeError(err.strip().splitlines()[-1] if err.strip() else f"ffmpeg exit {rc}")
        os.replace(it.path + ".part", it.path)
        it.filename = os.path.basename(it.path)
        it.size = it.downloaded = os.path.getsize(it.path)
        it.status = "done"

    def _ytdlp(self, it):
        exe = shutil.which("yt-dlp")
        cmd = [exe] if exe else [sys.executable, "-m", "yt_dlp"]
        custom = os.path.splitext(it.filename)[0].replace("%", "%%") if it.filename else None
        tmpl = os.path.join(self.dir, (custom or "%(title).150B") + ".%(ext)s")
        # YouTube needs a JS runtime to solve its player challenge; without one only low-quality formats appear.
        rt = next((r for r in ("node", "deno", "bun") if shutil.which(r)), None)
        if rt:
            cmd += ["--js-runtimes", rt]
        cmd += ["--newline", "--progress", "--no-playlist", "--merge-output-format", "mp4", "-o", tmpl, "--print", "after_move:filepath",
                "--progress-template", "download:PROG %(progress.downloaded_bytes)s %(progress.total_bytes_estimate)s %(progress.total_bytes)s"]
        # Deliberately no browser headers/cookies: logged-in YouTube cookies make yt-dlp use clients that need
        # PO tokens ("Requested format is not available"), and they would leak account cookies to a subprocess.
        cmd.append(it.url)
        it.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        out = []
        for line in it.proc.stdout:
            if line.startswith("PROG"):
                p = [x for x in line.split()[1:]]
                try:
                    it.downloaded = int(p[0])
                    it.size = int(next((x for x in (p[2], p[1]) if x.isdigit()), 0))
                except (ValueError, IndexError):
                    pass
            elif line.strip():
                out.append(line.strip())
        err = it.proc.stderr.read()
        rc, it.proc = it.proc.wait(), None
        if it.cancel.is_set():
            return
        if rc:
            if "No module named yt_dlp" in err:
                err = "yt-dlp not installed (pip install --user yt-dlp)"
            raise RuntimeError(err.strip().splitlines()[-1] if err.strip() else f"yt-dlp exit {rc}")
        it.path = out[-1] if out else None
        it.filename = os.path.basename(it.path or "")
        if it.path and os.path.exists(it.path):
            it.size = it.downloaded = os.path.getsize(it.path)
        it.status = "done"
