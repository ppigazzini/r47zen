#!/bin/bash

# Contract: the androidTest program-load bridge is excluded from builds by
# default. The bridge (jni_program_load_test.c) exports reset / inject /
# fd-override native entry points and the getXRegisterString glyph path; it must
# never ship to users. Only the instrumentation lanes opt in with
# -Pr47.includeProgramLoadTestBridge=true, so a plain assembleRelease or
# assembleDebug can never compile it. This guards against a future edit flipping
# the Gradle default back to true (which is how the entry points would silently
# re-enter a shipping artifact) and against the published release workflow
# enabling the bridge.
#
# Pure-host text check: no SDK, no staged native tree, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_GRADLE="$PROJECT_ROOT/android/app/build.gradle"
RELEASE_WORKFLOW="$PROJECT_ROOT/.github/workflows/android-release.yml"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[ -f "$BUILD_GRADLE" ] || fail "missing required file: ${BUILD_GRADLE#"$PROJECT_ROOT/"}"
[ -f "$RELEASE_WORKFLOW" ] || fail "missing required file: ${RELEASE_WORKFLOW#"$PROJECT_ROOT/"}"

# The Gradle default must resolve to false.
if ! grep -Eq "readBooleanProperty\('r47\.includeProgramLoadTestBridge',[[:space:]]*false\)" "$BUILD_GRADLE"; then
    fail "build.gradle no longer defaults includeProgramLoadTestBridge to false (the bridge would ship by default)."
fi

# The published release workflow must never opt the bridge in.
if grep -Eq 'includeProgramLoadTestBridge=true' "$RELEASE_WORKFLOW"; then
    fail "android-release.yml enables the program-load bridge in a published release build."
fi

echo "OK: the program-load test bridge is excluded by default (instrumentation lanes opt in)."
