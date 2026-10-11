#!/bin/bash

# Run clang-tidy over the Android-owned native glue with the pinned NDK's
# clang-tidy and android/app/src/main/cpp/r47zen/.clang-tidy, and fail on any
# finding. It reads the compile commands of an earlier native build, so run it
# after ./gradlew assembleDebug (or any build of an arm64-v8a variant); each
# glue file is checked with the command of the first build that compiled it.
# The staged upstream core is not checked: upstream owns it.
#
# Needs the Android SDK with the pinned NDK (ANDROID_SDK_ROOT or ANDROID_HOME).

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$sdk_root" ] || fail "ANDROID_SDK_ROOT or ANDROID_HOME must name the Android SDK."
ndk_version="$(bash "$SCRIPT_DIR/resolve_android_ndk_version.sh")"
clang_tidy="$sdk_root/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin/clang-tidy"
[ -x "$clang_tidy" ] || fail "no clang-tidy in NDK $ndk_version: $clang_tidy"

cd "$PROJECT_ROOT"
mapfile -t databases < <(find android/app/.cxx -path '*/arm64-v8a/compile_commands.json' 2>/dev/null | sort)
[ "${#databases[@]}" -gt 0 ] || fail "no arm64-v8a compile_commands.json under android/app/.cxx; build the app first."

# One "database-dir<TAB>file" line per glue translation unit.
mapfile -t units < <(
    python3 - "${databases[@]}" <<'PY'
import json, os, sys
seen = {}
for database in sys.argv[1:]:
    for entry in json.load(open(database)):
        source = entry["file"]
        if "/cpp/r47zen/" in source and source not in seen:
            seen[source] = os.path.dirname(database)
for source, directory in sorted(seen.items()):
    print(f"{directory}\t{source}")
PY
)
[ "${#units[@]}" -gt 0 ] || fail "the compile databases list no native glue file."

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
status=0
for unit in "${units[@]}"; do
    directory="${unit%%$'\t'*}"
    source="${unit#*$'\t'}"
    if ! "$clang_tidy" --quiet -p "$directory" "$source" >>"$log" 2>&1; then
        status=1
    fi
done

if [ "$status" -ne 0 ] || grep -q 'warning:\|error:' "$log"; then
    grep -E 'warning:|error:' "$log" >&2 || true
    fail "clang-tidy reported findings in the native glue."
fi
echo "OK: clang-tidy reports nothing in ${#units[@]} native glue files."
