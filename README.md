# my-tv-home

A calm, glass-style home screen ("My Home") for an Android TV 11 set (TCL, stock Android TV launcher):
large clock, Lahore weather, a dock of the streaming apps installed on the TV, and photographic
backgrounds. Plus the tooling used to build, install and visually verify it on the real TV.

```
android/home/      My Home app (Java, framework views only, no Gradle)
android/probe/     12 KB test app used to prove install / launch / uninstall
tools/build_apk.py Gradle-free build: aapt2 -> javac -> d8 -> zipalign -> apksigner
tools/tv.sh        ADB helper: deploy, start, key, shot, top, uninstall
tools/phonecam/    Private camera watcher (laptop webcam or phone) for visual feedback
docs/              Rollback, architecture / future phone control, credits
private/           (git-ignored) device inventory, SDK, signing key, logs, camera frames
```

## What runs where

| Feature | Needs |
|---|---|
| Clock, date, dock, backgrounds, glass UI | Nothing - fully on the TV, works offline |
| Weather (Open-Meteo) | Internet on the TV; cached and labelled "Offline · last updated..." when it can't refresh |
| Home-button helper | Enabled once in TV Settings > Device Preferences > Accessibility (see below) |
| Lighting | Not built - owner has no smart lights (provider slot reserved in docs/ARCHITECTURE.md) |

No cloud server or development computer is needed at runtime.

## Using it (remote only)

- **Left / Right** moves along the dock; **OK** opens the app. **Back** inside an app returns to My Home.
- **Up** from the dock reaches the buttons on the right: **Backgrounds**, **Arrange dock**, **Settings**,
  **Android TV Home**. **Down** returns to the app you last used.
- **Backgrounds**: Left/Right to browse, OK to apply. "Gentle motion" toggles a very slow drift (off by
  default; always off if the system "Remove animations" setting is on). Back closes.
- **Arrange dock**: every TV app is listed. OK on an app shows/hides it; the arrows move it earlier/later.
- **Android TV Home** (house icon): opens the stock home on purpose (for its Apps list, Search, inputs).
  The helper leaves it there for up to 10 minutes or until you come back to My Home.

### The Home button (important)
This TCL firmware always sends the Home button to the stock Android TV Home - even after My Home was
chosen in *Settings > Default home app* (verified: the HOME role is ours but the key/intent still go to
`com.google.android.tvlauncher`). The optional **Home-button helper** fixes this: an accessibility
service, limited to "window changed" events from the stock launcher only, with no access to screen
content, that switches to My Home whenever the stock home appears (about 0.1 s flash).

Turn it on: **Settings (gear) > Device Preferences > Accessibility > My Home: Home button > On**.
**Force-stopping My Home turns the helper off** (observed on this TV: `am force-stop`, or Settings > Apps >
My Home > Force stop). A normal update (`adb install -r`, as `tools/tv.sh deploy` does) keeps it on. If the
Home button ever opens the stock home again, check this switch first.

### Starting at power-on (TCL Safety Guard)
TCL's **Safety Guard > Permission Shield > Auto Launch Permission** blocks downloaded apps from starting at
boot ("Auto manager"). While My Home was "In auto management", a full restart (power cut, unplugging, or
the TV's own occasional cold boot) left the TV on the stock home *and* stopped the Home-button helper from
starting. Setting **Auto manager: Closed** and **My Home: Opened** fixed both - verified with a reboot:
My Home in front 63 s after power-on, helper running, Home button -> My Home.
(Safety Guard asks the owner to accept TCL's terms on first open.)

If the Home button ever opens the stock home again, My Home shows a small note at the bottom
(*"Home button helper is off"*): check the Accessibility switch and the Auto Launch Permission above.

## AI assistant

Press **Up** to the **mic** button (first in the pill) and press **OK**, or **hold OK** on any dock tile or
pill button. Speak when it says *Listening*; it stops by itself when you pause (press OK again to stop
early). Examples: "recommend a funny movie", "what's the weather this week", "make it louder",
"change the background", "open Netflix", "play some Coke Studio songs". Answers appear on a glass panel
and are spoken by the TV. Movie picks show as poster cards; **OK on a card opens its trailer on YouTube**.
**Ask again** continues the conversation; **Back** closes it.

How it works (all on the TV, no extra machine):
- **Voice**: My Home records from the TV's built-in far-field microphone only while you are asking
  (Android "Microphone" permission, granted once). The TV's Google voice-input screen is not used: on this
  TCL firmware it never opens the mic for apps (TCL's mic manager owns the remote/Assistant-button path),
  and the remote's Assistant button is reserved for Google Assistant.
- **Speech -> text**: Gemini 2.5 Flash-Lite. **Thinking/answers**: Gemini 2.5 Flash with a small allow-list of
  tools (`open_app`, `volume`, `set_background`, `get_weather`, `show_movies`, `youtube_search`), falling back
  to Flash-Lite / Gemini 3 Flash when the free per-minute limit is hit.
- **Posters**: Wikipedia page summaries (no key). **Speech out**: the TV's Google text-to-speech.
- **Key**: the owner's Google AI Studio key lives in `private/assistant.secrets.json` (git-ignored) and is
  copied into the APK at build time (`tools/build_apk.py`). Never commit it; anyone with the built APK file
  can extract it, so keep `private/build/` private.
- **Privacy**: audio is held in memory only and sent to Google for transcription; nothing is stored on the
  TV. On Google's free tier, prompts may be used to improve Google's products.
- **Limits**: no live web search on the free tier (the assistant says so for news/scores); it can't open a
  specific title inside Netflix/Prime; no smart-home devices yet (lights can be added later).
- OpenRouter is not used: its account currently has no credit and its data-policy settings exclude all
  providers; Gemini covers everything for free.

## Build and install (Windows, from this repo)

Prereqs (all local, no accounts): Python 3, JDK 17 (`winget install Microsoft.OpenJDK.17`),
platform-tools (`winget install Google.PlatformTools`), Android SDK build-tools 34 + platform 35 unzipped
into `private/sdk/bt` and `private/sdk/plat` (from dl.google.com, Android SDK License).

1. On the TV: Settings > Device Preferences > About > press **Build** 7 times; then Developer options >
   **USB debugging** on. Accept "Allow USB debugging" for this computer.
2. `cp .env.example .env` and set `TV_HOST` (the TV's LAN address).
3. `tools/tv.sh deploy` - builds, installs, launches, prints the start-up time.

The signing key is created on first build in `private/signing/` - **back it up**; updates to the
installed app must be signed with the same key.

## Rollback

See [docs/ROLLBACK.md](docs/ROLLBACK.md). Short version: turn the helper off in Accessibility (Home
button is stock again), or uninstall My Home (`tools/tv.sh uninstall`, or Settings > Apps > My Home >
Uninstall). Nothing on the TV was disabled, removed, rooted or flashed.

## Credits and licences
- Photos: Unsplash License - see `android/home/assets/bg/credits.json` (also shown on screen).
- Weather: [Open-Meteo.com](https://open-meteo.com), free non-commercial API, data CC BY 4.0 (credited on screen).
- Camera-watcher idea from [victaphu/glass-home-samsung-tv](https://github.com/victaphu/glass-home-samsung-tv)
  `tools/phonecam.py`; rewritten here with authentication, expiry and freshness checks (no code copied).
- Icons are drawn in code (`Glyph.java`); no third-party icon sets.

## Live TV

The **LIVE TV** tile (first in the dock) opens a channel screen with tabs - News, Entertainment,
Religious, Kids, Sports & Music. **OK** on a channel starts it; voice works too ("play Geo News").

| Source | Channels | How it plays |
|---|---|---|
| Official 24/7 YouTube live streams | Geo News, ARY News, Samaa, Express, Dunya, Dawn, PTV News, 92 News, Aaj, GNN, BOL News, HUM News, Suno, 24 News, Neo, City 42, Lahore News, Public News, Capital TV, ARY Qtv | My Home looks up the channel's current live video and opens it in YouTube |
| Free public streams from [iptv-org](https://github.com/iptv-org/iptv) (open source, Unlicense), only those that respond from this network | ~40, e.g. Hum TV, Bol Entertainment, Zindagi, Filmax, Discover Pakistan, Madani, Peace TV Urdu, Minimax, PK Sports | My Home's own full-screen player (platform MediaPlayer/HLS). **Up/Down** = next/previous channel in the tab, **OK** = channel info, **Back** = close |
| Official network apps | ARY Digital, ARY Zindagi, A Sports, ARY Musik (ARY ZAP) | opens the app |

Refresh the list (free streams come and go): `python tools/build_live_list.py` then `tools/tv.sh deploy`.
`tools/check_streams.py <m3u>` tests any playlist from this network.

Notes: iptv-org lists only *publicly available* streams, but it does not guarantee each stream is
authorised by the broadcaster - prefer the official sources. Channels like Geo Entertainment, ARY Digital,
PTV Home/Sports or Geo Super are not offered as free public streams; they are available legally through the
networks' own apps (ARY ZAP) or Jazz's Tamasha app (free with ads, some channels need a plan). No pirated
streams are included. The TV's built-in tuner (antenna/cable) is unused: none is connected.
