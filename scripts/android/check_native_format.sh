#!/bin/bash

# Check that the Android-owned native glue (android/app/src/main/cpp/r47zen)
# matches its .clang-format, with the clang-format of the pinned NDK, so the
# check and the toolchain that compiles the glue are one LLVM release. With
# --fix, rewrite the files instead. The staged upstream core is not checked:
# upstream owns its style.
#
# Needs the Android SDK with the pinned NDK (ANDROID_SDK_ROOT or ANDROID_HOME).

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

mode=(--dry-run --Werror)
case "${1:-}" in
    --fix) mode=(-i) ;;
    '') ;;
    *) fail "usage: $0 [--fix]" ;;
esac

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$sdk_root" ] || fail "ANDROID_SDK_ROOT or ANDROID_HOME must name the Android SDK."
ndk_version="$(bash "$SCRIPT_DIR/resolve_android_ndk_version.sh")"
clang_format="$sdk_root/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin/clang-format"
[ -x "$clang_format" ] || fail "no clang-format in NDK $ndk_version: $clang_format"

cd "$PROJECT_ROOT"
mapfile -t files < <(git ls-files 'android/app/src/main/cpp/r47zen/*.c' 'android/app/src/main/cpp/r47zen/*.h')
[ "${#files[@]}" -gt 0 ] || fail "found no native glue sources to check"

if ! "$clang_format" "${mode[@]}" "${files[@]}"; then
    fail "the native glue is not clang-formatted; run $0 --fix"
fi
echo "OK: ${#files[@]} native glue files match .clang-format ($("$clang_format" --version | sed 's/.*version \([0-9.]*\).*/clang-format \1/'))."
