"""Private phone-camera watcher.

Two listeners, both bound to 127.0.0.1:
  * PUBLIC_PORT  - phone-facing page + upload API. Only this port is tunnelled.
  * ADMIN_PORT   - local-only control API (pairing, freshness marks, latest
                   frame). Never tunnelled.

Security model
  * Pairing: random single-use code with expiry, delivered in the URL fragment
    (never sent to the server in the URL, so never in access logs/referrers).
    The page POSTs it once and receives an HttpOnly/Secure/SameSite=Strict
    session cookie plus a CSRF token required on every state-changing call.
  * Sessions are short-lived and revocable (Stop Camera, `watch.py revoke`).
  * Freshness: the admin side bumps an "epoch" after an action has settled.
    The phone tags each capture with the epoch it knew *at capture time*, so a
    frame tagged >= E was provably captured after mark E regardless of clocks
    or upload delays.

Reference idea: victaphu/glass-home-samsung-tv tools/phonecam.py (MIT); this is
a rewrite that adds authentication, expiry, sequencing and retention limits.
"""
import hashlib, hmac, http.server, json, os, secrets, socketserver, sys, threading, time
from pathlib import Path
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "private"
FRAMES = PRIVATE / "frames"
STATIC = Path(__file__).resolve().parent / "static"
PUBLIC_PORT = int(os.environ.get("PHONECAM_PUBLIC_PORT", "8765"))
ADMIN_PORT = int(os.environ.get("PHONECAM_ADMIN_PORT", "8766"))
PAIR_TTL = 10 * 60
SESSION_TTL = 2 * 60 * 60
MAX_FRAME = 3 * 1024 * 1024
KEEP_FRAMES = 3
QUALITY = {
    "normal": {"width": 1280, "quality": 0.62, "interval_ms": 3000},
    "sharp": {"width": 1920, "quality": 0.85, "interval_ms": 3000},
}

lock = threading.Condition()
state = {
    "pair_hash": None, "pair_expires": 0, "pair_attempts": 0,
    "sessions": {},            # sid_hash -> {csrf, expires, last_client_seq}
    "epoch": 0, "epoch_time": 0.0,
    "server_seq": 0, "latest": None,
    "mode": "normal",
    "phone": {"visible": None, "camera": None, "last_status": 0.0, "note": ""},
}


def _h(v):
    return hashlib.sha256(v.encode()).hexdigest()


def _now():
    return time.time()


def _write_json(path, obj):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=2))
    os.replace(tmp, path)


def _session(handler):
    raw = handler.headers.get("Cookie", "")
    sid = None
    for part in raw.split(";"):
        k, _, v = part.strip().partition("=")
        if k == "pc_sess":
            sid = v
    if not sid:
        return None, None
    key = _h(sid)
    s = state["sessions"].get(key)
    if not s or s["expires"] < _now():
        state["sessions"].pop(key, None)
        return None, None
    return key, s


class Quiet(http.server.BaseHTTPRequestHandler):
    server_version = "phonecam"
    sys_version = ""

    def log_message(self, fmt, *args):
        # Path only (no query, no headers/cookies) to keep secrets out of logs.
        p = urlparse(self.path).path
        with open(PRIVATE / "logs" / "phonecam.log", "a") as f:
            f.write("%s %s %s %s\n" % (time.strftime("%H:%M:%S"), self.command, p, args[1] if len(args) > 1 else ""))

    def _send(self, code, body=b"", ctype="application/json", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _body(self, limit):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0 or n > limit:
            return None
        return self.rfile.read(n)


class PublicHandler(Quiet):
    CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; "
           "media-src 'self' blob: mediastream:; connect-src 'self'; base-uri 'none'; "
           "form-action 'none'; frame-ancestors 'none'")

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            return self._static("index.html", "text/html; charset=utf-8")
        if path == "/app.js":
            return self._static("app.js", "application/javascript")
        if path == "/app.css":
            return self._static("app.css", "text/css")
        if path == "/api/epoch":
            return self._epoch_poll()
        self._send(404, {"error": "not found"})

    def _static(self, name, ctype):
        data = (STATIC / name).read_bytes()
        self._send(200, data, ctype, {"Content-Security-Policy": self.CSP,
                                      "Permissions-Policy": "camera=(self), microphone=()"})

    def _authed(self, need_csrf=True):
        with lock:
            key, s = _session(self)
        if not s:
            self._send(401, {"error": "not paired"})
            return None, None
        if need_csrf and not hmac.compare_digest(self.headers.get("X-CSRF", ""), s["csrf"]):
            self._send(403, {"error": "bad csrf"})
            return None, None
        return key, s

    def _epoch_poll(self):
        key, s = self._authed(need_csrf=True)
        if not s:
            return
        q = parse_qs(urlparse(self.path).query)
        known = int(q.get("known", ["-1"])[0])
        deadline = _now() + 20
        with lock:
            while state["epoch"] == known and state["mode"] == q.get("mode", [state["mode"]])[0] and _now() < deadline:
                lock.wait(deadline - _now())
            out = {"epoch": state["epoch"], "mode": state["mode"], **QUALITY[state["mode"]]}
        self._send(200, out)

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/api/pair":
            return self._pair()
        if path == "/api/frame":
            return self._frame()
        if path == "/api/status":
            return self._status()
        if path == "/api/stop":
            return self._stop()
        self._send(404, {"error": "not found"})

    def _pair(self):
        body = self._body(1024)
        try:
            code = json.loads(body or b"{}").get("code", "")
        except ValueError:
            code = ""
        with lock:
            ok = (state["pair_hash"] and state["pair_expires"] > _now()
                  and hmac.compare_digest(_h(code), state["pair_hash"]))
            if not ok:
                state["pair_attempts"] += 1
                if state["pair_attempts"] >= 10:
                    state["pair_hash"] = None  # burn the code after repeated failures
                return self._send(403, {"error": "invalid or expired pairing code"})
            state["pair_hash"] = None  # single use
            sid, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(24)
            state["sessions"] = {_h(sid): {"csrf": csrf, "expires": _now() + SESSION_TTL,
                                           "last_client_seq": -1}}  # one phone at a time
            out = {"csrf": csrf, "epoch": state["epoch"], "mode": state["mode"], **QUALITY[state["mode"]],
                   "expires_in": SESSION_TTL}
        cookie = "pc_sess=%s; Path=/api; HttpOnly; Secure; SameSite=Strict; Max-Age=%d" % (sid, SESSION_TTL)
        self._send(200, out, extra={"Set-Cookie": cookie})

    def _frame(self):
        key, s = self._authed()
        if not s:
            return
        if self.headers.get("Content-Type") != "image/jpeg":
            return self._send(415, {"error": "jpeg only"})
        data = self._body(MAX_FRAME)
        if not data or data[:2] != b"\xff\xd8":
            return self._send(400, {"error": "bad frame"})
        try:
            client_seq = int(self.headers.get("X-Frame-Seq", "-1"))
            seen_epoch = int(self.headers.get("X-Seen-Epoch", "-1"))
            meta = json.loads(self.headers.get("X-Capture-Meta", "{}"))
        except ValueError:
            return self._send(400, {"error": "bad headers"})
        with lock:
            if client_seq <= s["last_client_seq"]:
                return self._send(409, {"error": "out-of-order frame"})
            s["last_client_seq"] = client_seq
            state["server_seq"] += 1
            seq = state["server_seq"]
            name = "frame-%06d.jpg" % seq
            (FRAMES / name).write_bytes(data)
            latest = {"server_seq": seq, "client_seq": client_seq, "seen_epoch": min(seen_epoch, state["epoch"]),
                      "received_at": _now(), "bytes": len(data), "file": name,
                      "width": meta.get("w"), "height": meta.get("h"), "mode": meta.get("mode")}
            state["latest"] = latest
            state["phone"].update(visible=True, camera=True, last_status=_now())
            _write_json(FRAMES / "latest.json", latest)
            for old in sorted(FRAMES.glob("frame-*.jpg"))[:-KEEP_FRAMES]:
                old.unlink(missing_ok=True)
            lock.notify_all()
            out = {"server_seq": seq, "epoch": state["epoch"], "mode": state["mode"], **QUALITY[state["mode"]]}
        self._send(200, out)

    def _status(self):
        key, s = self._authed()
        if not s:
            return
        try:
            st = json.loads(self._body(2048) or b"{}")
        except ValueError:
            st = {}
        with lock:
            state["phone"].update(visible=bool(st.get("visible")), camera=bool(st.get("camera")),
                                  last_status=_now(), note=str(st.get("note", ""))[:120])
            lock.notify_all()
        self._send(200, {"ok": True})

    def _stop(self):
        key, s = self._authed()
        if not s:
            return
        with lock:
            state["sessions"].pop(key, None)
            state["phone"].update(camera=False, visible=False, last_status=_now(), note="stopped by phone")
            lock.notify_all()
        self._send(200, {"ok": True}, extra={"Set-Cookie": "pc_sess=; Path=/api; Max-Age=0; Secure; HttpOnly; SameSite=Strict"})


class AdminHandler(Quiet):
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/admin/status":
            with lock:
                lt = state["latest"]
                out = {"epoch": state["epoch"], "mode": state["mode"], "paired": bool(state["sessions"]),
                       "pair_pending": bool(state["pair_hash"]) and state["pair_expires"] > _now(),
                       "phone": state["phone"], "latest": lt,
                       "latest_age_s": round(_now() - lt["received_at"], 1) if lt else None}
            return self._send(200, out)
        if path == "/admin/wait":
            return self._wait()
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.client_address[0] != "127.0.0.1":
            return self._send(403, {"error": "local only"})
        path = urlparse(self.path).path
        q = parse_qs(urlparse(self.path).query)
        if path == "/admin/pair":
            code = secrets.token_urlsafe(24)
            with lock:
                state.update(pair_hash=_h(code), pair_expires=_now() + PAIR_TTL, pair_attempts=0)
            return self._send(200, {"code": code, "expires_in": PAIR_TTL})
        if path == "/admin/mark":
            with lock:
                state["epoch"] += 1
                state["epoch_time"] = _now()
                lock.notify_all()
                return self._send(200, {"epoch": state["epoch"]})
        if path == "/admin/mode":
            mode = q.get("mode", ["normal"])[0]
            if mode not in QUALITY:
                return self._send(400, {"error": "mode"})
            with lock:
                state["mode"] = mode
                lock.notify_all()
            return self._send(200, {"mode": mode})
        if path == "/admin/revoke":
            with lock:
                state["sessions"].clear()
                state["pair_hash"] = None
                lock.notify_all()
            return self._send(200, {"ok": True})
        self._send(404, {"error": "not found"})

    def _wait(self):
        q = parse_qs(urlparse(self.path).query)
        epoch = int(q.get("epoch", ["0"])[0])
        deadline = _now() + float(q.get("timeout", ["20"])[0])
        with lock:
            while _now() < deadline:
                lt = state["latest"]
                if lt and lt["seen_epoch"] >= epoch:
                    return self._send(200, {"ok": True, "frame": lt, "path": str(FRAMES / lt["file"])})
                lock.wait(max(0.05, deadline - _now()))
            return self._send(504, {"ok": False, "error": "no frame captured after mark", "phone": state["phone"],
                                    "latest": state["latest"]})


class TServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = False


def main():
    for d in (FRAMES, PRIVATE / "logs"):
        d.mkdir(parents=True, exist_ok=True)
    for old in FRAMES.glob("frame-*.jpg"):
        old.unlink()
    pub = TServer(("127.0.0.1", PUBLIC_PORT), PublicHandler)
    adm = TServer(("127.0.0.1", ADMIN_PORT), AdminHandler)
    threading.Thread(target=adm.serve_forever, daemon=True).start()
    print("phonecam public=127.0.0.1:%d admin=127.0.0.1:%d" % (PUBLIC_PORT, ADMIN_PORT), flush=True)
    try:
        pub.serve_forever()
    finally:
        adm.shutdown()


if __name__ == "__main__":
    sys.exit(main())
