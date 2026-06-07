#!/bin/bash

# Resolve the Android NDK version to build against, and refuse to silently use
# the wrong toolchain.
#
# An explicitly pinned NDK (R47_NDK_VERSION, which CI and the release lane always
# set) MUST be installed: if it is absent this fails loudly instead of falling
# back to whatever NDK happens to be present, which would build -- and ship -- a
# release against an unpinned toolchain. When R47_NDK_VERSION is unset (local
# default-version builds), an absent default may fall back to the newest
# installed NDK for developer convenience.
#
# Inputs (env):
#   ANDROID_SDK_ROOT or ANDROID_HOME  - required SDK root that holds ndk/<ver>.
#   R47_NDK_VERSION                   - optional explicit pin (CI/release set it).
#   R47_DEFAULT_ANDROID_NDK_VERSION   - default pin; if unset, read from
#                                       android/r47-defaults.properties.
# Output: the resolved NDK version on stdout. Diagnostics go to stderr.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$sdk_root" ]; then
    echo "resolve_android_ndk_version: ANDROID_SDK_ROOT/ANDROID_HOME is not set." >&2
    exit 1
fi

pinned="${R47_NDK_VERSION:-}"
default="${R47_DEFAULT_ANDROID_NDK_VERSION:-}"
if [ -z "$default" ] && [ -f "$PROJECT_ROOT/android/r47-defaults.properties" ]; then
    default="$(sed -n 's/^R47_DEFAULT_ANDROID_NDK_VERSION=//p' \
        "$PROJECT_ROOT/android/r47-defaults.properties" | head -n1)"
fi

effective="${pinned:-$default}"

# The requested version is installed: use it.
if [ -n "$effective" ] && [ -d "$sdk_root/ndk/$effective" ]; then
    printf '%s\n' "$effective"
    exit 0
fi

# An explicit pin that is missing is fatal: never substitute another toolchain.
if [ -n "$pinned" ]; then
    echo "resolve_android_ndk_version: pinned NDK '$pinned' is not installed under $sdk_root/ndk." >&2
    echo "Install it with: sdkmanager \"ndk;$pinned\"  (or unset R47_NDK_VERSION for a local fallback build)." >&2
    exit 1
fi

# No explicit pin: fall back to the newest installed NDK (local convenience).
latest="$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null |
    sort -V | tail -n1)"
if [ -n "$latest" ]; then
    echo "resolve_android_ndk_version: default NDK '${effective:-unset}' not found; falling back to newest installed '$latest'." >&2
    printf '%s\n' "$latest"
    exit 0
fi

echo "resolve_android_ndk_version: no NDK is installed under $sdk_root/ndk." >&2
exit 1
