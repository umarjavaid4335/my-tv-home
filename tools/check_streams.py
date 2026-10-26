"""Check which channels in an M3U playlist actually respond from this network (HLS manifest reachable).

  python tools/check_streams.py https://iptv-org.github.io/iptv/countries/pk.m3u [out.m3u]

Writes a playlist of working channels (default private/live/pk-working.m3u) and prints a summary.
Only reads the first few KB of each stream's manifest; nothing is recorded.
"""
import concurrent.futures, re, sys, time, urllib.request
from pathlib import Path

src = sys.argv[1]
out = Path(sys.argv[2] if len(sys.argv) > 2 else Path(__file__).resolve().parents[1] / "private/live/pk-working.m3u")
UA = "Mozilla/5.0 (Linux; Android 11; TV) my-tv-home/1.0"

text = urllib.request.urlopen(urllib.request.Request(src, headers={"User-Agent": UA}), timeout=30).read().decode("utf-8", "replace")
entries, extinf, opts = [], None, []
for line in text.splitlines():
    if line.startswith("#EXTINF"):
        extinf, opts = line, []
    elif line.startswith("#EXTVLCOPT") or line.startswith("#KODIPROP"):
        opts.append(line)
    elif line.strip() and not line.startswith("#") and extinf:
        entries.append((extinf, opts, line.strip()))
        extinf = None


def check(e):
    extinf, opts, url = e
    headers = {"User-Agent": UA}
    for o in opts:  # honour per-stream referrer / user-agent hints from the playlist
        m = re.match(r"#EXTVLCOPT:http-(referrer|user-agent)=(.*)", o)
        if m:
            headers["Referer" if m.group(1) == "referrer" else "User-Agent"] = m.group(2)
    t = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=12)
        head = r.read(4096)
        ok = r.status == 200 and (b"#EXTM3U" in head or b"#EXT-X" in head or url.endswith((".ts", ".mp4")))
        return e, ok, f"{r.status} {time.time()-t:.1f}s"
    except Exception as ex:
        return e, False, type(ex).__name__ + " " + str(getattr(ex, "code", ""))


with concurrent.futures.ThreadPoolExecutor(16) as pool:
    results = list(pool.map(check, entries))

good = [r for r in results if r[1]]
out.parent.mkdir(parents=True, exist_ok=True)
with open(out, "w", encoding="utf-8") as f:
    f.write("#EXTM3U\n")
    for (extinf, opts, url), _, _ in good:
        f.write(extinf + "\n" + "".join(o + "\n" for o in opts) + url + "\n")
name = lambda x: x.split(",", 1)[-1]
print(f"working {len(good)} / {len(results)}  ->  {out}")
print("WORKING:", " | ".join(sorted(name(e[0]) for e, _, _ in good)))
