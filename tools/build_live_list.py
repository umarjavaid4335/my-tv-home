"""Build My Home's Live TV channel list (android/home/assets/live/channels.json).

Sources, all free:
  * official 24/7 YouTube live streams of Pakistani channels (handles below; checked live now),
  * publicly available streams from the open-source iptv-org project (countries/pk.m3u), keeping only
    those that respond from this network,
  * official network apps installed on the TV (opened, not streamed by us).

  python tools/build_live_list.py      # then tools/tv.sh deploy
Re-run occasionally: free streams come and go.
"""
import concurrent.futures, json, re, sys, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/home/assets/live/channels.json"
UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/141 Safari/537.36"
UA_TV = "Mozilla/5.0 (Linux; Android 11; TV) my-tv-home/1.0"

# Official channels found via YouTube search / handle checks (2026-10-26).
YOUTUBE = [
    ("Geo News", "geonews"), ("ARY News", "ArynewsTvofficial"), ("Samaa TV", "samaatv"),
    ("Express News", "ExpressNewspkofficial"), ("Dunya News", "DunyanewsOfficial"), ("Dawn News", "dawnnewspakistan"),
    ("PTV News", "PTVNewsOfficial"), ("92 News HD", "92newshdTv"), ("Aaj News", "aajtvofficial"),
    ("GNN", "gnnhdofficial"), ("BOL News", "BOLNewsOfficial"), ("HUM News", "HUMNewsPakistan"),
    ("Suno News", "SunoNewsHD"), ("24 News HD", "24NewsHD"), ("Neo News", "NeoNews_Live"),
    ("City 42", "City42Lahore"), ("Lahore News HD", "LahoreNewsOfficial"), ("Public News", "PublicNewsPK"),
    ("Capital TV", "CapitalTVLive"), ("ARY Qtv", "ARYQtvofficial"),
]
# Channels only offered through an official app on this TV (opened via the app).
APPS = [
    ("ARY Digital", "com.release.aryzaptv", "Entertainment"), ("ARY Zindagi", "com.release.aryzaptv", "Entertainment"),
    ("A Sports", "com.release.aryzaptv", "Sports & Music"), ("ARY Musik", "com.release.aryzaptv", "Sports & Music"),
]
CATEGORY = {  # iptv-org group-title -> our tabs
    "News": "News", "Entertainment": "Entertainment", "Movies": "Entertainment", "Series": "Entertainment",
    "General": "Entertainment", "Family": "Entertainment", "Lifestyle": "Entertainment", "Culture": "Entertainment",
    "Religious": "Religious", "Kids": "Kids", "Animation": "Kids", "Music": "Sports & Music", "Sports": "Sports & Music",
    "Documentary": "Entertainment", "Education": "Entertainment",
}


def get(url, ua=UA_DESKTOP, limit=None, timeout=20, headers=None):
    h = {"User-Agent": ua, "Accept-Language": "en", "Cookie": "CONSENT=YES+1", **(headers or {})}
    r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)
    return r.status, (r.read(limit) if limit else r.read())


def youtube(entry):
    name, handle = entry
    try:
        _, html = get(f"https://www.youtube.com/@{handle}/live")
        html = html.decode("utf-8", "replace")
        cid = re.search(r'"externalId":"(UC[\w-]{22})"', html) or re.search(r'"channelId":"(UC[\w-]{22})"', html)
        live = bool(re.search(r'<link rel="canonical" href="https://www.youtube.com/watch\?v=', html))
        # The channel page's og:image is the channel avatar (the /live page's is the video thumbnail).
        _, page = get(f"https://www.youtube.com/@{handle}", limit=1_200_000)
        logo = re.search(r'<meta property="og:image" content="([^"]+)"', page.decode("utf-8", "replace"))
        return {"name": name, "type": "youtube", "channel_id": cid.group(1) if cid else None, "handle": handle,
                "category": "News", "logo": logo.group(1).replace("=s900", "=s240") if logo else None, "live_now": live}
    except Exception as e:
        return {"name": name, "type": "youtube", "handle": handle, "error": str(e)}


def norm(n):
    return re.sub(r"[^a-z0-9]", "", re.sub(r"\(.*?\)|\[.*?\]|\bhd\b|\bsd\b", "", n.lower()))


def streams():
    _, text = get("https://iptv-org.github.io/iptv/countries/pk.m3u", ua=UA_TV, timeout=30)
    out, cur = [], None
    for line in text.decode("utf-8", "replace").splitlines():
        if line.startswith("#EXTINF"):
            name = line.split(",", 1)[-1].strip()
            cur = {"name": re.sub(r"\s*\((\d+p)\)", "", name).replace(" [Not 24/7]", "").strip(),
                   "group": (re.search(r'group-title="([^"]*)"', line) or [None, ""])[1],
                   "logo": (re.search(r'tvg-logo="([^"]*)"', line) or [None, None])[1], "headers": {},
                   "not247": "[Not 24/7]" in name}
        elif line.startswith("#EXTVLCOPT") and cur:
            m = re.match(r"#EXTVLCOPT:http-(referrer|user-agent)=(.*)", line)
            if m:
                cur["headers"]["Referer" if m.group(1) == "referrer" else "User-Agent"] = m.group(2)
        elif line.strip() and not line.startswith("#") and cur:
            cur["url"] = line.strip()
            out.append(cur)
            cur = None

    def ok(s):
        try:
            st, head = get(s["url"], ua=s["headers"].get("User-Agent", UA_TV), limit=4096, timeout=12,
                           headers={k: v for k, v in s["headers"].items() if k != "User-Agent"})
            return st == 200 and (b"#EXTM3U" in head or b"#EXT-X" in head)
        except Exception:
            return False

    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        alive = [s for s, good in zip(out, pool.map(ok, out)) if good]
    res = []
    for s in alive:
        cat = next((CATEGORY[g] for g in s["group"].split(";") if g in CATEGORY), "Entertainment")
        res.append({"name": s["name"], "type": "stream", "url": s["url"], "headers": s["headers"],
                    "category": cat, "logo": s["logo"], "not247": s["not247"], "source": "iptv-org"})
    return res, len(out)


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        yt = list(pool.map(youtube, YOUTUBE))
    yt_ok = [c for c in yt if c.get("channel_id")]
    st, total = streams()
    official = {norm(c["name"]) for c in yt_ok}
    st = [s for s in st if norm(s["name"]) not in official]          # prefer the official YouTube stream
    seen, st_unique = set(), []
    for s in st:
        if norm(s["name"]) not in seen:
            seen.add(norm(s["name"]))
            st_unique.append(s)
    apps = [{"name": n, "type": "app", "package": p, "category": c} for n, p, c in APPS]
    channels = yt_ok + st_unique + apps
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"generated": __import__("time").strftime("%Y-%m-%d"), "channels": channels},
                              ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"YouTube official: {len(yt_ok)}/{len(YOUTUBE)} resolved, live now: {sum(c['live_now'] for c in yt_ok)}")
    print(f"iptv-org streams working: {len(st_unique)} (of {total}, after removing duplicates of official ones)")
    print(f"apps: {len(apps)}  ->  {OUT.relative_to(ROOT)} ({len(channels)} channels)")
    for c in yt:
        if not c.get("channel_id"):
            print("  unresolved:", c["name"], c.get("error", ""))
