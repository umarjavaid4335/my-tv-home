"""Control the phone-camera watcher.

  python tools/phonecam/watch.py start     # server + HTTPS quick tunnel (background)
  python tools/phonecam/watch.py pair      # new single-use QR (private/pair-qr.png)
  python tools/phonecam/watch.py shot [--settle 1.5] [--timeout 25] [--sharp]
        # waits for a frame *captured after now+settle*; prints its path
  python tools/phonecam/watch.py status
  python tools/phonecam/watch.py stop      # revoke, stop tunnel+server, delete frames
"""
import argparse, json, os, re, shutil, signal, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "private"
RUNTIME = PRIVATE / "phonecam-runtime.json"
ADMIN = "http://127.0.0.1:%s" % os.environ.get("PHONECAM_ADMIN_PORT", "8766")
PUBLIC_PORT = os.environ.get("PHONECAM_PUBLIC_PORT", "8765")


def admin(method, path, timeout=10):
    req = urllib.request.Request(ADMIN + path, method=method, data=b"" if method == "POST" else None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def find_cloudflared():
    exe = shutil.which("cloudflared")
    if exe:
        return exe
    for p in Path(os.environ.get("LOCALAPPDATA", "")).glob("Microsoft/WinGet/Packages/Cloudflare.cloudflared*/**/cloudflared*.exe"):
        return str(p)
    for p in (Path(r"C:\Program Files (x86)\cloudflared\cloudflared.exe"), Path(r"C:\Program Files\cloudflared\cloudflared.exe")):
        if p.exists():
            return str(p)
    sys.exit("cloudflared not found")


def cmd_start(a):
    (PRIVATE / "logs").mkdir(parents=True, exist_ok=True)
    flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS if os.name == "nt" else 0
    srv_log = open(PRIVATE / "logs" / "server.out", "w")
    srv = subprocess.Popen([sys.executable, "-I", str(Path(__file__).with_name("server.py"))],
                           stdout=srv_log, stderr=subprocess.STDOUT, creationflags=flags)
    cf_log_path = PRIVATE / "logs" / "cloudflared.log"
    cf_log = open(cf_log_path, "w")
    cf = subprocess.Popen([find_cloudflared(), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:%s" % PUBLIC_PORT],
                          stdout=cf_log, stderr=subprocess.STDOUT, creationflags=flags)
    url = None
    for _ in range(60):
        time.sleep(1)
        m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", cf_log_path.read_text(errors="replace"))
        if m:
            url = m.group(0)
            break
    RUNTIME.write_text(json.dumps({"server_pid": srv.pid, "cloudflared_pid": cf.pid, "url": url}, indent=2))
    print("server pid", srv.pid, "| tunnel pid", cf.pid, "| tunnel", "ready" if url else "NOT READY")


def _kill(pid):
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass


def cmd_pair(a):
    rt = json.loads(RUNTIME.read_text())
    if a.local and rt.get("cloudflared_pid"):
        # Laptop webcam: http://localhost is already a secure context, so drop the public tunnel.
        _kill(rt["cloudflared_pid"])
        rt.update(cloudflared_pid=None, url=None)
        RUNTIME.write_text(json.dumps(rt, indent=2))
        print("tunnel stopped; local-only mode")
    st, j = admin("POST", "/admin/pair")
    if st != 200 or not (rt.get("url") or a.local):
        sys.exit("pairing failed: %s %s" % (st, j))
    if a.local:
        import webbrowser
        webbrowser.open("http://localhost:%s/#c=%s" % (PUBLIC_PORT, j["code"]))
        print("opened the camera page in the default browser (code single use, %d min)" % (j["expires_in"] // 60))
        return
    link = "%s/#c=%s" % (rt["url"], j["code"])
    import qrcode
    img = qrcode.make(link, box_size=10, border=3)
    out = PRIVATE / "pair-qr.png"
    img.save(out)
    print("QR written to", out, "(expires in %d min, single use)" % (j["expires_in"] // 60))


def cmd_status(a):
    print(json.dumps(admin("GET", "/admin/status")[1], indent=2))


def cmd_shot(a):
    admin("POST", "/admin/mode?mode=%s" % ("sharp" if a.sharp else "normal"))
    time.sleep(a.settle)
    _, m = admin("POST", "/admin/mark")
    st, j = admin("GET", "/admin/wait?epoch=%d&timeout=%s" % (m["epoch"], a.timeout), timeout=a.timeout + 5)
    if st != 200:
        print(json.dumps(j, indent=2))
        sys.exit("STALE: no fresh frame - pause TV operations until feedback resumes")
    f = j["frame"]
    dst = PRIVATE / "frames" / "check.jpg"
    shutil.copyfile(j["path"], dst)
    print("%s  seq=%d epoch>=%d %sx%s %dKB" % (dst, f["server_seq"], m["epoch"], f["width"], f["height"], f["bytes"] // 1024))


def cmd_stop(a):
    try:
        admin("POST", "/admin/revoke", timeout=3)
    except Exception:
        pass
    if RUNTIME.exists():
        rt = json.loads(RUNTIME.read_text())
        for k in ("cloudflared_pid", "server_pid"):
            pid = rt.get(k)
            if pid:
                _kill(pid)
        RUNTIME.unlink()
    for p in list((PRIVATE / "frames").glob("*")) + [PRIVATE / "pair-qr.png"]:
        p.unlink(missing_ok=True)
    print("stopped; session revoked; temporary frames deleted")


def main():
    p = argparse.ArgumentParser()
    sp = p.add_subparsers(dest="cmd", required=True)
    for n in ("start", "status", "stop"):
        sp.add_parser(n)
    sp.add_parser("pair").add_argument("--local", action="store_true",
                                       help="use this computer's webcam via http://localhost (no tunnel)")
    s = sp.add_parser("shot")
    s.add_argument("--settle", type=float, default=1.5)
    s.add_argument("--timeout", type=float, default=25)
    s.add_argument("--sharp", action="store_true")
    a = p.parse_args()
    globals()["cmd_" + a.cmd](a)


if __name__ == "__main__":
    main()
