# my-tv-home - progress

Private device details (addresses, identifiers) live in `private/device-inventory.md` (git-ignored).

## Decisions
- **Platform: Android TV 11 (TCL "BeyondTV", stock Android TV launcher; it is not a Google TV build).** Verified over ADB.
- **App tech: native Android, framework views only (Java), built with SDK tools directly (no Gradle).**
  The TV has a 32-bit Realtek SoC, 2 GB RAM (~0.5 GB free) and GLES 2.0, so Compose/WebView UIs and
  live blur are avoided. "Glass" = blurred background copies computed once per background change and
  drawn behind panels.
- **Camera feedback: laptop webcam via http://localhost** (secure context; no tunnel, nothing exposed).
  Phone/tunnel mode remains available (`watch.py pair` without `--local`).
- Home app runs first as a normal LEANBACK_LAUNCHER app; default-home handling only if the device offers a
  supported, reversible way.
- Preferences: weather Lahore, °C, 12-hour clock, timezone Asia/Karachi. No smart lights for now.

## Verified observations (2026-10-26)
- Camera loop: fresh frame -> ADB Back -> later frame showed the menu change (Developer options -> Device Preferences).
- Second feedback channel: `adb exec-out screencap -p` returns full-res 1920x1080 PNG.
- Camera caught the Ambient Mode screensaver covering a launched app - camera is required, ADB state alone isn't enough.
- Probe APK (12 KB, signed): install OK, cold launch ~1.9 s, visible on TV via camera, Back exits,
  `adb uninstall` OK, stock launcher returns with all apps.

## Blockers / needs from owner
- None right now.

## Next action
- Build the home app (clock, weather, dock, backgrounds), install, verify with remote + camera.
