# my-tv-home - progress

Private device details (addresses, identifiers) live in `private/device-inventory.md` (git-ignored).

## Decisions
- **Platform: Android TV 11 (TCL "BeyondTV", stock Android TV launcher; it is not a Google TV build).**
- **App tech: native Android, framework views only (Java), built with SDK tools directly (no Gradle).**
  32-bit Realtek SoC, 2 GB RAM (~0.5 GB free), GLES 2.0 -> no Compose/WebView, no live blur. Glass is a
  192x108 blurred copy computed once per background change.
- **Camera feedback: laptop webcam via http://localhost** (secure context; no tunnel). Phone/tunnel mode
  remains available (`watch.py pair` without `--local`).
- **Home button:** default-home role is honoured by Settings but not by this firmware, so an optional,
  narrowly-scoped accessibility helper (owner-enabled) redirects the stock home to My Home.
- Preferences: weather Lahore, °C, 12-hour clock, Asia/Karachi. No smart lights (lighting deferred).

## Verified observations (2026-10-26, real TV)
| Check | Result | Evidence |
|---|---|---|
| Camera loop | fresh frame -> ADB Back -> later frame showed the menu change | webcam frames |
| Probe app | install, cold launch ~1.9 s, visible, Back exits, uninstall OK | camera + adb |
| My Home UI | clock (12 h), Lahore weather + 4-day forecast, 6 dock apps, glass panels | adb screencap |
| Remote | Left/Right/Up/Down/OK/Back; sheets restore focus to opener; no traps | 6-step screencap sheet |
| Backgrounds | picker applies photo; glass re-blurs; credit updates | screencap |
| App launch / return | YouTube from dock; Back returns to My Home with YouTube focused | adb + camera |
| Default home app | My Home selectable; HOME role set; **firmware still opens stock home** | dumpsys role, screencap |
| Home helper | Home -> stock -> My Home in ~0.1 s | logcat + camera |
| Android TV Home button | stock home stays (10-min allowance) | adb |
| Standby (Power) -> wake | wakes into My Home; no reboot (Quick Start) | owner + camera + uptime |
| Full restart (before fix) | boot_completed 64 s; stock home; helper never bound, BOOT_COMPLETED never delivered | dumpsys accessibility, logcat |
| Root cause | TCL Safety Guard "Auto Launch Permission" (Auto manager) blocks third-party boot starts | Safety Guard UI, TclActivityManagerService logs |
| Full restart (after owner allowed My Home) | My Home in front 63 s after reboot; helper bound; Home -> My Home | adb + camera |
| Layout | single grid: 64 dp sides, 48 dp top/bottom, 16 dp gaps; right column + full-width dock aligned | screencap |
| Force-stop | switches the helper off; plain `install -r` keeps it | dumpsys accessibility |
| Launch times | cold 1.2-2.1 s (2.1 s just after boot), warm ~0.9-1.0 s (`am start -W`) | adb |
| Idle | ~0.16 CPU-s per minute (~0.3 % of one core), ~29 MB PSS | /proc stat, meminfo |

### AI assistant (2026-10-26)
| Check | Result |
|---|---|
| Remote's Assistant button | TCL-private broadcast to com.tcl.assistant -> Google Assistant; not available to apps |
| RecognizerIntent (Google voice bar) | opens, but never captures on this firmware (com.tcl.micmanager owns the mic) |
| AudioRecord (VOICE_RECOGNITION) | works after RECORD_AUDIO grant; room ~40-70 rms, speech ~1300-9000, first 300 ms burst ignored |
| Owner voice tests | "recommend a feel-good family movie" -> 4 poster cards; "open YouTube" -> opened; weather -> forecast; "make it louder", "change the background", "recommend a funny movie" -> all worked |
| Latency | transcription ~1.8-3 s, answer ~0.8-2.9 s after speech ends |
| Movie card OK | GLOBAL_SEARCH does nothing visible; YouTube `results?search_query=` deep link verified |
| Free quota | 2.5 Flash hit the per-minute limit during rapid tests -> model fallback added |

The "helper is off" note was seen on screen while the helper was unbound, and disappears once it runs.

## Blockers / needs from owner
- None. Keep Safety Guard > Auto Launch Permission > My Home = Opened. Avoid Force stop on My Home
  (switches the helper off until it is toggled again).

## Next action
- Optional: phone control / lighting per docs/ARCHITECTURE.md when wanted.
