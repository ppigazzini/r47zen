#!/bin/bash
# Launch a build in the shipped shape on a connected emulator and type a sum.
#
# The instrumented lanes run the release with R8 off and the test bridge in,
# so neither the minified dex nor a library built without the bridge ever ran
# before a tester. The release lane builds the app as it
# ships, R8, resource shrinking, and the release's PGO profile, for x86_64,
# and this script installs it, starts its launcher activity and waits for the
# first frame, types 2 ENTER 3 + through the hardware-key path into the native
# core, and fails if the process died or restarted, or if the crash buffer
# names the package: a Java exception, as a keep rule R8 lost would raise when
# JNI_OnLoad binds its methods, or a native tombstone.
#
# Usage: run_shipped_build_smoke.sh --apk <file.apk> --package <application id>
#                                   [--serial <adb serial>]

set -Eeuo pipefail

# Seconds the app runs after the last key before the checks read it.
SETTLE_SECONDS="${R47_SHIPPED_SMOKE_SETTLE_SECONDS:-5}"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

apk=""
package=""
serial=""
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --apk | --package | --serial)
            [[ "$#" -ge 2 ]] || fail "Missing value for $1"
            case "$1" in
                --apk) apk="$2" ;;
                --package) package="$2" ;;
                --serial) serial="$2" ;;
            esac
            shift 2
            ;;
        *) fail "Unknown option: $1" ;;
    esac
done
[[ -f "$apk" ]] || fail "No APK at '${apk}'."
[[ -n "$package" ]] || fail "--package is required."
command -v adb >/dev/null 2>&1 || fail "adb is required on PATH."

if [[ -z "$serial" ]]; then
    mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [[ "${#devices[@]}" -eq 1 ]] ||
        fail "Expected one connected device, found ${#devices[@]}; pass --serial."
    serial="${devices[0]}"
fi

device() { adb -s "$serial" "$@"; }
# adb shell ends each line with CR.
device_shell() { device shell "$@" | tr -d '\r'; }

device uninstall "$package" >/dev/null 2>&1 || true
device install -r "$apk" || fail "adb install refused ${apk}."
device logcat -b all -c

component="$(device_shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" | tail -n 1)"
[[ "$component" == "$package/"* ]] ||
    fail "${package} has no launcher activity: '${component}'."

# -W returns once the activity has drawn its first frame.
launch="$(device_shell am start -W -n "$component")"
printf '%s\n' "$launch"
grep -qx 'Status: ok' <<<"$launch" || fail "The launch of ${component} did not report Status: ok."

# pidof exits 1 when nothing matches, and adb passes that on.
pid="$(device_shell pidof "$package" || true)"
[[ -n "$pid" ]] || fail "${package} is not running after its launch."

# 2 ENTER 3 +: hardware keys reach the core through pc_keyboard.c, as GTK's do.
device_shell input text 2
device_shell input keyevent KEYCODE_ENTER
device_shell input text 3
device_shell input text +
sleep "$SETTLE_SECONDS"

pid_after="$(device_shell pidof "$package" || true)"
crashes="$(device_shell logcat -b crash -d | grep -F -- "$package" || true)"
if [[ -n "$crashes" ]]; then
    printf '%s\n' "$crashes" >&2
    fail "The crash buffer names ${package}."
fi
[[ "$pid_after" == "$pid" ]] ||
    fail "${package} ran as pid ${pid} at launch and '${pid_after}' after the keys."

echo "Shipped-shape smoke passed: ${component} drew its first frame and ran 2 ENTER 3 + as pid ${pid}."
