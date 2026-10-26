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

**After a full restart (power cut, unplugging, reboot) this TCL firmware switches accessibility off**:
the master switch is reset (`accessibility_enabled=0`) although Settings still lists the helper as "On".
The TV then starts on the stock home, and My Home shows a small note at the bottom: *"Home button helper
is off"*. Fix: Accessibility > My Home: Home button > Off, then On again. Normal standby (Power button,
"Quick Start") is not affected - the TV wakes straight back into My Home.

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
