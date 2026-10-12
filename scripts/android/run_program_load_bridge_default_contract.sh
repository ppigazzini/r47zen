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
# The same holds for the storage tests' host activity: build.gradle defaults
# includeInstrumentationHost to false, and build-production-release-bundle,
# the job whose bytes ship, never turns it on.
#
# Pure-host text check: no SDK, no staged native tree, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
BUILD_GRADLE="$PROJECT_ROOT/android/app/build.gradle"
RELEASE_WORKFLOW="$PROJECT_ROOT/.github/workflows/android-release.yml"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[ -f "$BUILD_GRADLE" ] || fail "missing required file: ${BUILD_GRADLE#"$PROJECT_ROOT/"}"
[ -f "$RELEASE_WORKFLOW" ] || fail "missing required file: ${RELEASE_WORKFLOW#"$PROJECT_ROOT/"}"

# Live code only: a `//`, `/* */`, or `#` comment must not satisfy or violate a
# check. The Gradle default must resolve to false.
live_gradle="$(strip_c_comments "$BUILD_GRADLE")"
if ! grep -Eq "readBooleanProperty\('r47\.includeProgramLoadTestBridge',[[:space:]]*false\)" <<<"$live_gradle"; then
    fail "build.gradle no longer defaults includeProgramLoadTestBridge to false (the bridge would ship by default)."
fi

# The published release workflow must never opt the bridge in.
live_release="$(strip_hash_comments <"$RELEASE_WORKFLOW")"
if grep -Eq 'includeProgramLoadTestBridge=true' <<<"$live_release"; then
    fail "android-release.yml enables the program-load bridge in a published release build."
fi

# The storage tests' host activity: off by default, and never on in the job
# whose bytes ship. The verify job, which builds a throwaway-signed rebuild
# for the emulator, may turn it on.
if ! grep -Eq "readBooleanProperty\('r47\.includeInstrumentationHost',[[:space:]]*false\)" <<<"$live_gradle"; then
    fail "build.gradle no longer defaults includeInstrumentationHost to false (the test host activity would ship)."
fi
shipping_job="$(workflow_job_block "$RELEASE_WORKFLOW" build-production-release-bundle | strip_hash_comments || true)"
[ -n "$shipping_job" ] || fail "android-release.yml has no build-production-release-bundle job; the scan read nothing."
if grep -Eq 'includeInstrumentationHost=true' <<<"$shipping_job"; then
    fail "build-production-release-bundle declares the test host activity in the shipped release."
fi

echo "OK: the program-load test bridge and the test host activity are excluded by default (instrumentation lanes opt in)."
