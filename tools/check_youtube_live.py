"""Which official YouTube channels are live right now (24/7 news streams).

  python tools/check_youtube_live.py            # checks the candidate handles below
Prints channel, handle, live/not live and the current live video id.
"""
import concurrent.futures, json, re, urllib.request

CANDIDATES = {
    "Geo News": ["geonews"], "ARY News": ["arynewsofficial", "ARYNews"], "Samaa TV": ["samaatv"],
    "Express News": ["expressnewspk", "ExpressNewsOfficial"], "Dunya News": ["DunyanewsOfficial", "dunyanews"],
    "92 News HD": ["92newshd", "92NewsHDPlus"], "GNN": ["gnnhdofficial", "GNN"], "Aaj News": ["AajNewsOfficial", "aajnewstv"],
    "Hum News": ["humnewspk", "HUMNewsPakistan"], "BOL News": ["BOLNewsOfficial", "bolnews"],
    "PTV News": ["PTVNewsOfficial", "ptvnews"], "Public News": ["PublicNewsOfficial", "publicnews"],
    "Neo News": ["neonewspk", "NeoNewsHD"], "Suno News": ["SunoNewsHD", "sunonews"], "24 News HD": ["24NewsHD"],
    "City 42": ["city42", "City42News"], "Abb Takk": ["abbtakk", "AbbTakkNews"], "Dawn News": ["dawnnewspakistan", "DawnNewsTV"],
    "Lahore Rang": ["lahorerang"], "Khyber News": ["khybernews"], "Capital TV": ["capitaltvpk"],
    "PTV Sports": ["PTVSPORTSOFFICIALpk"], "PTV World": ["PTVWorldOfficial"], "Madani Channel": ["madanichannel"],
}
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/141 Safari/537.36"


def probe(name, handles):
    for h in handles:
        try:
            req = urllib.request.Request(f"https://www.youtube.com/@{h}/live",
                                         headers={"User-Agent": UA, "Accept-Language": "en", "Cookie": "CONSENT=YES+1"})
            html = urllib.request.urlopen(req, timeout=20).read().decode("utf-8", "replace")
        except Exception as e:
            continue
        canon = re.search(r'<link rel="canonical" href="([^"]+)"', html)
        live = '"isLiveNow":true' in html or '"isLive":true' in html
        vid = re.search(r"watch\?v=([\w-]{11})", canon.group(1)) if canon else None
        title = re.search(r'"author":"([^"]+)"', html) or re.search(r'<meta name="title" content="([^"]+)"', html)
        cid = re.search(r'"channelId":"(UC[\w-]{22})"', html)
        if canon:
            return name, h, live and bool(vid), vid.group(1) if vid else None, title.group(1) if title else "?", cid.group(1) if cid else None
    return name, handles[0], False, None, "not found", None


with concurrent.futures.ThreadPoolExecutor(8) as pool:
    rows = list(pool.map(lambda kv: probe(*kv), CANDIDATES.items()))
for name, h, live, vid, author, cid in sorted(rows, key=lambda r: (not r[2], r[0])):
    print(f"{'LIVE ' if live else '---- '} {name:16s} @{h:22s} {cid or '':26s} {vid or '':12s} {author[:40]}")
print(json.dumps({r[0]: {"handle": r[1], "channel_id": r[5]} for r in rows if r[2]}, indent=0)[:0])
