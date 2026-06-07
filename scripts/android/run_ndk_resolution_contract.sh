#!/bin/bash

# Contract test for resolve_android_ndk_version.sh. Runs on the host, no SDK or
# device needed: it builds fake ndk/<version> directory trees and asserts the
# resolver's behavior. The load-bearing case is "pinned NDK missing -> fatal",
# which guards against a release silently built against an unpinned toolchain.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVER="$SCRIPT_DIR/resolve_android_ndk_version.sh"

pass_count=0
fail_count=0

# make_sdk <name> <ndk-version>...  -> prints a fresh SDK root holding those NDKs
make_sdk() {
    local root
    root="$(mktemp -d)"
    shift
    local version
    for version in "$@"; do
        mkdir -p "$root/ndk/$version"
    done
    printf '%s\n' "$root"
}

# expect <desc> <expected-exit> <expected-stdout>  (env vars set by caller)
expect() {
    local desc="$1" want_exit="$2" want_out="$3"
    local out rc
    set +e
    out="$(bash "$RESOLVER" 2>/dev/null)"
    rc=$?
    set -e

    if [ "$rc" -eq "$want_exit" ] && [ "$out" = "$want_out" ]; then
        echo "OK: $desc"
        pass_count=$((pass_count + 1))
    else
        echo "FAIL: $desc -> exit=$rc out='$out' (expected exit=$want_exit out='$want_out')"
        fail_count=$((fail_count + 1))
    fi
}

PIN="29.0.14206865"
OTHER="99.9.9"
OLDER="77.7.7"

# 1. Pinned NDK installed: resolves to it.
sdk="$(make_sdk pinned-present "$PIN")"
R47_NDK_VERSION="$PIN" R47_DEFAULT_ANDROID_NDK_VERSION="$PIN" ANDROID_SDK_ROOT="$sdk" \
    expect "pinned NDK installed resolves to the pin" 0 "$PIN"
rm -rf "$sdk"

# 2. THE GUARD: pinned NDK absent (only a different one installed) -> fatal.
sdk="$(make_sdk pinned-absent "$OTHER")"
R47_NDK_VERSION="$PIN" R47_DEFAULT_ANDROID_NDK_VERSION="$PIN" ANDROID_SDK_ROOT="$sdk" \
    expect "pinned NDK absent is fatal (no silent fallback)" 1 ""
rm -rf "$sdk"

# 3. No pin, default installed: resolves to the default.
sdk="$(make_sdk default-present "$PIN")"
R47_DEFAULT_ANDROID_NDK_VERSION="$PIN" ANDROID_SDK_ROOT="$sdk" \
    expect "unpinned default installed resolves to the default" 0 "$PIN"
rm -rf "$sdk"

# 4. No pin, default absent: falls back to the newest installed NDK.
sdk="$(make_sdk default-fallback "$OLDER" "$OTHER")"
R47_DEFAULT_ANDROID_NDK_VERSION="$PIN" ANDROID_SDK_ROOT="$sdk" \
    expect "unpinned default absent falls back to newest installed" 0 "$OTHER"
rm -rf "$sdk"

# 5. No NDK installed at all: fatal.
sdk="$(make_sdk none)"
mkdir -p "$sdk/ndk"
R47_NDK_VERSION="$PIN" R47_DEFAULT_ANDROID_NDK_VERSION="$PIN" ANDROID_SDK_ROOT="$sdk" \
    expect "no NDK installed is fatal" 1 ""
rm -rf "$sdk"

echo "--- NDK resolution contract: ${pass_count} passed, ${fail_count} failed ---"
[ "$fail_count" -eq 0 ]
