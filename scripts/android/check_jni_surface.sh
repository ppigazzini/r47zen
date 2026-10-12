#!/bin/bash

# Check that a minified APK still carries every name the native bridge looks up
# at runtime. R8 renames and removes what no keep rule names, and a JNI lookup
# by a name R8 changed fails only on a device: RegisterNatives in JNI_OnLoad
# aborts the library load, a GetMethodID returns NULL. The expected names come
# from the C sources, not from a list here:
#
#   - the class MAIN_ACTIVITY_CLASS in jni_bridge.h names, which FindClass loads;
#   - every {"name", "signature", ...} entry of the RegisterNatives table in
#     jni_registration.c, each of which must be a native method of that class;
#   - every GetMethodID(env, clazz, "name", "signature") in the glue, each of
#     which must be a method of that class.
#
# Usage:
#   scripts/android/check_jni_surface.sh --apk <app.apk> [--android-sdk-root <dir>]
#   scripts/android/check_jni_surface.sh --dexdump-output <file>
#
# --dexdump-output reads saved `dexdump` text instead of an APK, for the
# contract's fixtures. The APK form runs dexdump from the pinned build-tools
# over every classes*.dex.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GLUE_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
DEFAULTS_FILE="$PROJECT_ROOT/android/r47-defaults.properties"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

apk=""
dexdump_output=""
android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk)
            apk="$2"
            shift 2
            ;;
        --dexdump-output)
            dexdump_output="$2"
            shift 2
            ;;
        --android-sdk-root)
            android_sdk_root="$2"
            shift 2
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done
[[ -n "$apk" || -n "$dexdump_output" ]] || fail "Pass --apk or --dexdump-output."

# --- the names the C sources look up ---------------------------------------
class_path="$(sed -n 's/^#define MAIN_ACTIVITY_CLASS "\([^"]*\)".*/\1/p' "$GLUE_DIR/jni_bridge.h")"
[[ -n "$class_path" ]] || fail "No MAIN_ACTIVITY_CLASS in $GLUE_DIR/jni_bridge.h."
class_descriptor="L${class_path};"

natives="$(grep -oE '\{"[A-Za-z0-9_]+", "[^"]+",' "$GLUE_DIR/jni_registration.c" |
    sed -E 's/^\{"([^"]+)", "([^"]+)",$/\1 \2/' | sort -u)"
[[ -n "$natives" ]] || fail "No RegisterNatives entry found in $GLUE_DIR/jni_registration.c."

glue_sources=("$GLUE_DIR"/*.c "$GLUE_DIR"/hal/*.c)
callbacks="$(cat "${glue_sources[@]}" | tr '\n' ' ' |
    grep -oE 'GetMethodID\([^,]+,[^,]+,[[:space:]]*"[A-Za-z0-9_]+",[[:space:]]*"[^"]+"' |
    sed -E 's/.*"([A-Za-z0-9_]+)",[[:space:]]*"([^"]+)"$/\1 \2/' | sort -u)"
[[ -n "$callbacks" ]] || fail "No GetMethodID call found in $GLUE_DIR."

# --- the methods the dex holds ------------------------------------------------
if [[ -n "$apk" ]]; then
    [[ -f "$apk" ]] || fail "APK not found: $apk"
    [[ -n "$android_sdk_root" ]] || fail "Set ANDROID_SDK_ROOT or pass --android-sdk-root."
    build_tools_version="$(sed -n 's/^R47_DEFAULT_ANDROID_BUILD_TOOLS_VERSION=//p' "$DEFAULTS_FILE")"
    dexdump="$android_sdk_root/build-tools/$build_tools_version/dexdump"
    [[ -x "$dexdump" ]] || fail "dexdump not found at $dexdump."
    dex_dir="$(mktemp -d)"
    trap 'rm -rf "$dex_dir"' EXIT
    unzip -q -o "$apk" 'classes*.dex' -d "$dex_dir"
    dexdump_output="$dex_dir/dexdump.txt"
    for dex in "$dex_dir"/classes*.dex; do
        "$dexdump" "$dex" >>"$dexdump_output"
    done
fi
[[ -s "$dexdump_output" ]] || fail "No dexdump output to read."

# One "name signature NATIVE|-" line per method of the class.
methods="$(awk -v want="$class_descriptor" '
    /^  Class descriptor/ {
        in_class = (index($0, "'\''" want "'\''") > 0)
        in_methods = 0
        next
    }
    !in_class { next }
    /^  (Direct|Virtual) methods/ { in_methods = 1; next }
    /^  (Static|Instance) fields/ { in_methods = 0; next }
    in_methods && /^      name/ { name = $0; sub(/.*: '\''/, "", name); sub(/'\''.*/, "", name); next }
    in_methods && /^      type/ { type = $0; sub(/.*: '\''/, "", type); sub(/'\''.*/, "", type); next }
    in_methods && /^      access/ {
        print name, type, (($0 ~ /NATIVE/) ? "NATIVE" : "-")
    }
' "$dexdump_output" | sort -u)"
[[ -n "$methods" ]] || fail "The dex holds no class $class_descriptor, or no method in it."

# has_method NAME SIGNATURE [NATIVE]: true when the class has that exact method.
has_method() {
    awk -v name="$1" -v signature="$2" -v native="${3:-}" '
        $1 == name && $2 == signature && (native == "" || $3 == native) { found = 1 }
        END { exit !found }
    ' <<<"$methods"
}

missing=()
while read -r name signature; do
    has_method "$name" "$signature" NATIVE ||
        missing+=("native $name$signature (RegisterNatives)")
done <<<"$natives"
while read -r name signature; do
    has_method "$name" "$signature" ||
        missing+=("$name$signature (GetMethodID)")
done <<<"$callbacks"

if [[ "${#missing[@]}" -gt 0 ]]; then
    printf 'ERROR: %s lacks a name the native bridge looks up:\n' "$class_descriptor" >&2
    printf '  %s\n' "${missing[@]}" >&2
    exit 1
fi
echo "JNI surface intact: $class_descriptor carries $(grep -c . <<<"$natives") registered natives and $(grep -c . <<<"$callbacks") GetMethodID callbacks."
