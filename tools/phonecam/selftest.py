"""Local self-test of the watcher's auth + freshness rules (run with server.py running, no tunnel)."""
import json, threading, time, urllib.request

A, P = "http://127.0.0.1:8766", "http://127.0.0.1:8765"


def req(url, method="GET", data=None, headers=None):
    r = urllib.request.Request(url, method=method, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(r, timeout=30) as x:
            return x.status, x.headers, x.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def check(label, got, want):
    print("%-40s %s %s" % (label, got, "OK" if got == want else "FAIL (want %s)" % want))


J = {"Content-Type": "application/json"}
check("frame without session", req(P + "/api/frame", "POST", b"\xff\xd8x", {"Content-Type": "image/jpeg"})[0], 401)
code = json.loads(req(A + "/admin/pair", "POST", b"")[2])["code"]
check("wrong pairing code", req(P + "/api/pair", "POST", json.dumps({"code": "nope"}).encode(), J)[0], 403)
s, h, b = req(P + "/api/pair", "POST", json.dumps({"code": code}).encode(), J)
check("correct pairing code", s, 200)
print("  cookie flags:", h["Set-Cookie"].split(";", 1)[1].strip())
csrf, sid = json.loads(b)["csrf"], h["Set-Cookie"].split(";")[0].split("=", 1)[1]
check("pairing code reuse", req(P + "/api/pair", "POST", json.dumps({"code": code}).encode(), J)[0], 403)
H = {"Cookie": "pc_sess=" + sid, "Content-Type": "image/jpeg", "X-CSRF": csrf}
check("frame without CSRF", req(P + "/api/frame", "POST", b"\xff\xd8a", {k: v for k, v in H.items() if k != "X-CSRF"})[0], 403)
check("frame seq1 epoch0", req(P + "/api/frame", "POST", b"\xff\xd8a", {**H, "X-Frame-Seq": "1", "X-Seen-Epoch": "0"})[0], 200)
ep = json.loads(req(A + "/admin/mark", "POST", b"")[2])["epoch"]
res = {}
t = threading.Thread(target=lambda: res.update(w=req(A + "/admin/wait?epoch=%d&timeout=6" % ep)))
t.start(); time.sleep(0.4)
check("late upload of pre-mark capture", req(P + "/api/frame", "POST", b"\xff\xd8b", {**H, "X-Frame-Seq": "2", "X-Seen-Epoch": "0"})[0], 200)
time.sleep(0.4)
check("  ...does not satisfy wait", t.is_alive(), True)
check("out-of-order client seq", req(P + "/api/frame", "POST", b"\xff\xd8c", {**H, "X-Frame-Seq": "2", "X-Seen-Epoch": str(ep)})[0], 409)
req(P + "/api/frame", "POST", b"\xff\xd8d", {**H, "X-Frame-Seq": "3", "X-Seen-Epoch": str(ep)})
t.join()
w = json.loads(res["w"][2])
check("post-mark capture satisfies wait", (res["w"][0], w["frame"]["seen_epoch"] >= ep), (200, True))
check("stop revokes session", req(P + "/api/stop", "POST", b"", {"Cookie": "pc_sess=" + sid, "X-CSRF": csrf})[0], 200)
check("frame after stop", req(P + "/api/frame", "POST", b"\xff\xd8e", {**H, "X-Frame-Seq": "9"})[0], 401)
