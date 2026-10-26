# Rollback and recovery

Everything done to the TV is ordinary and reversible. Nothing was rooted, flashed, disabled or removed;
the stock Android TV launcher (`com.google.android.tvlauncher`) and TCL's fallback homes are untouched.

## 1. Stock Home button back (keep My Home installed)
Remote: **Settings > Device Preferences > Accessibility > My Home: Home button > Off**.
The Home button opens the stock Android TV Home again immediately. My Home stays available as an app.

Optional, for completeness: **Settings > Apps > Default apps > Home app** (on this TV the screen title is
"Default home app"; also reachable with `adb shell am start -a android.settings.HOME_SETTINGS`) and pick
**Android TV Home**. (On this firmware this choice does not change the Home button either way.)

To stop My Home opening at power-on: **Safety Guard > Permission Shield > Auto Launch Permission > My Home >
Closed** (or set Auto manager back to Opened, which was the original state).

## 2. Remove My Home completely
- Remote: **Settings > Apps > See all apps > My Home > Uninstall**, or
- Computer: `tools/tv.sh uninstall` (= `adb uninstall dev.mytvhome.home`).

Uninstalling also removes the helper, its settings and the cached weather. The HOME role returns to the
system default (Android TV Home).

## 3. Stop developer access
**Settings > Device Preferences > Developer options > USB debugging > Off** (and optionally turn
Developer options off). In Developer options, **Revoke USB debugging authorisations** removes this
computer's key. Re-enabling later needs the "Build" x7 step again.

## 4. If something goes wrong
- My Home crashes or freezes: press **Home** (helper off -> stock home) or turn the helper off as in 1.
  Worst case, uninstall from Settings > Apps.
- Black screen after the helper switches: press **Back**, then **Home**; the stock launcher and Settings
  are always reachable from the remote's Settings/gear in the stock home.
- Probe test app (`dev.mytvhome.probe`) was installed and uninstalled during setup; nothing remains.

## Verified on this TV (2026-10-26)
- Probe install -> launch -> uninstall: OK, stock launcher returned with all apps.
- My Home uninstall path is the same command as the probe's.
