#!/usr/bin/env bash
# Small ADB helper for the TV. Reads TV_HOST / ADB_CONNECT_PORT from .env (git-ignored).
#   tools/tv.sh deploy            build + install + (re)start My Home, print cold-start time
#   tools/tv.sh start             launch My Home
#   tools/tv.sh key RIGHT [n]     send a remote key (DPAD_*, CENTER, BACK, HOME, WAKEUP...) n times
#   tools/tv.sh shot NAME         save an ADB screenshot to private/evidence/NAME.png
#   tools/tv.sh top               show the resumed activity
#   tools/tv.sh uninstall         remove My Home (rollback)
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
S="${TV_HOST:?set TV_HOST in .env}:${ADB_CONNECT_PORT:-5555}"
ADB="${ADB:-$(command -v adb || echo "$LOCALAPPDATA/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe")}"
PKG=dev.mytvhome.home
adb_() { "$ADB" -s "$S" "$@"; }
adb_ get-state >/dev/null 2>&1 || "$ADB" connect "$S" >/dev/null

case "${1:-}" in
  deploy)
    python -I tools/build_apk.py android/home | tail -1
    adb_ install -r private/build/home.apk | tail -1
    adb_ shell am force-stop $PKG
    adb_ shell input keyevent KEYCODE_WAKEUP
    adb_ shell am start -W -n $PKG/.HomeActivity | grep -E "TotalTime" ;;
  start) adb_ shell am start -W -n $PKG/.HomeActivity | grep -E "Status|TotalTime" ;;
  key)
    for _ in $(seq "${3:-1}"); do adb_ shell input keyevent "KEYCODE_$2"; sleep 0.35; done ;;
  shot) mkdir -p private/evidence; adb_ exec-out screencap -p > "private/evidence/$2.png"; echo "private/evidence/$2.png" ;;
  top) adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity" ;;
  uninstall) adb_ uninstall $PKG ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
