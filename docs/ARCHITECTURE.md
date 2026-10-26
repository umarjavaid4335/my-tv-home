# Architecture and future phone control

## Today
```
TV (Android TV 11)                                       Internet
┌──────────────────────────────────────────┐
│ My Home (dev.mytvhome.home)               │
│  HomeActivity  clock/dock/sheets/focus    │
│  Backdrop      photo + pre-blurred copy   │
│  GlassPanel    draws blurred region       │
│  Weather       Open-Meteo + cache  ───────┼──────► api.open-meteo.com (every 30 min)
│  AppRepo       LEANBACK_LAUNCHER query    │
│  HomeHelperService (optional a11y)        │
└──────────────────────────────────────────┘
```
Why native framework views: the TV has a 32-bit Realtek SoC, 2 GB RAM (~0.5 GB free) and GLES 2.0.
Live blur (RenderEffect) needs API 31; WebView/Compose add start-up cost and memory. Glass is drawn
from a 192x108 blurred copy computed once per background change, so idle CPU is ~0 (no animations
unless "Gentle motion" is on; the clock updates on the system minute tick).

## Planned (deferred until requested): family phone control
Goal: a family-only phone PWA with a shared drawing pad, lighting controls and voice commands
("open Netflix", "make the room darker").

```
Phone PWA ──HTTPS──► Bridge (always-on machine or small hosted service) ◄──WebSocket── My Home (TV)
                       │  auth, pairing, last drawing, light providers
                       └─► light provider(s): local LAN API first, cloud only if no LAN API
```
- **Pairing**: TV shows a short-lived QR (same pattern as `tools/phonecam`: single-use code in the URL
  fragment -> session cookie + CSRF). Per-device tokens, revocable from the TV.
- **Access control**: family members are paired devices; no public sign-up. The TV only accepts
  commands from the bridge it paired with.
- **Commands**: a small allow-list (`launch <package from AppRepo>`, `background <n>`, `lights <preset>`),
  never arbitrary intents.
- **Drawing pad**: bridge stores the latest drawing; the TV fetches it on reconnect, so it survives the
  TV being off.
- **Voice**: phone microphone + on-phone speech recognition; the remote's microphone is not assumed.
- **Lighting**: `LightProvider` interface (power, brightness, colour, white temperature, scenes) with
  capabilities discovered per device; unsupported controls hidden. Credentials live on the bridge only,
  never in the TV app. The owner currently has no smart lights.
- **Hosting**: a home machine is enough; a hosted option (e.g. Fly.io) only if remote access is wanted,
  and only with the owner's approval of any cost.
