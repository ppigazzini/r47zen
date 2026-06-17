#!/bin/bash

# Contract: the bridge ThreadSanitizer lane keeps its hard gate. The lane only
# means something if (1) a data race in the Android-owned bridge actually fails
# it and (2) the suppression escape hatch can never be widened to silence such a
# race. This guard locks both invariants with pure-host text checks (no SDK, no
# staged native tree, no sanitizer build), so a later edit that quietly drops
# halt_on_error, the thread sanitizer, or scopes a suppression to the bridge is
# caught fast in run_workflow_contracts.sh rather than by an absent race report.
#
# What proves the lane itself: scripts/workload-regressions/build_bridge_tsan_
# harness.sh, run on every pull request in the linux-ci host-workload lane.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WR_DIR="$PROJECT_ROOT/scripts/workload-regressions"
BUILD_SCRIPT="$WR_DIR/build_bridge_tsan_harness.sh"
HARNESS_SRC="$WR_DIR/bridge_tsan_harness.c"
SUPPRESSIONS="$WR_DIR/bridge_tsan_suppressions.txt"
CI_WORKFLOW="$PROJECT_ROOT/.github/workflows/linux-ci.yml"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

for f in "$BUILD_SCRIPT" "$HARNESS_SRC" "$SUPPRESSIONS" "$CI_WORKFLOW"; do
    [ -f "$f" ] || fail "missing required file: ${f#"$PROJECT_ROOT/"}"
done

# 1. The build is a real ThreadSanitizer build with the hard gate armed.
grep -Eq -- '-fsanitize=thread' "$BUILD_SCRIPT" ||
    fail "build_bridge_tsan_harness.sh no longer builds under -fsanitize=thread."
grep -Eq 'halt_on_error=1' "$BUILD_SCRIPT" ||
    fail "build_bridge_tsan_harness.sh dropped halt_on_error=1 (the hard gate)."
grep -q 'bridge_tsan_suppressions.txt' "$BUILD_SCRIPT" ||
    fail "build_bridge_tsan_harness.sh no longer loads the suppression file."

# 2. The suppression file stays upstream-scoped: no active (non-comment) entry
#    may silence a race in the Android-owned bridge. Comments documenting the
#    rule are allowed; an actual suppression line targeting the bridge is not.
bridge_suppressions="$(grep -vE '^[[:space:]]*(#|$)' "$SUPPRESSIONS" |
    grep -E 'android/app/src/main/cpp/r47zen|r47zen/' || true)"
if [ -n "$bridge_suppressions" ]; then
    fail "bridge_tsan_suppressions.txt suppresses an Android-owned bridge race (the hard gate): $bridge_suppressions"
fi

# 3. The lane is actually wired into CI beside the other host-workload lanes.
grep -q 'build_bridge_tsan_harness.sh' "$CI_WORKFLOW" ||
    fail "linux-ci.yml does not run build_bridge_tsan_harness.sh."

echo "OK: bridge ThreadSanitizer lane keeps -fsanitize=thread + halt_on_error, upstream-scoped suppressions, and CI wiring."
