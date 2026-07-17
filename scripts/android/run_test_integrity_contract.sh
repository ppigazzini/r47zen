#!/bin/bash

# Contract: the test-integrity guards stay in place, so a test lane cannot
# silently pass having tested nothing.
#
# 1. count_androidtest_cases (scripts/lib/common.sh) is exercised functionally
#    against synthetic JUnit result XML: it must sum <testsuite tests="N">
#    counts and report 0 for an empty or missing directory. The connected lane
#    fails a selection that reports success with 0 executed cases.
# 2. The host workload correctness lane must fail on an outer-timeout kill
#    (124/137) rather than tolerating it unconditionally, and must fail on a
#    bounded-stop failure (exit 3) rather than tolerating it unconditionally.
# 3. The mutation spot-check must compile a mutant before running its tests, so
#    a non-compiling mutant is not miscounted as killed, and must score the
#    mutant from the JUnit results XML rather than the raw Gradle exit code, so a
#    renamed/deleted test class ("No tests found") is not a phantom kill.
#
# Pure-host check: no SDK, no staged native tree, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

WORKLOAD_RUNNER="$PROJECT_ROOT/scripts/workload-regressions/run_workload_regressions.sh"
MUTATION="$PROJECT_ROOT/scripts/android/mutation_spot_check.sh"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# --- 1. count_androidtest_cases functional test ------------------------------
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

[ "$(count_androidtest_cases "$tmp")" = "0" ] ||
    fail "count_androidtest_cases reported non-zero for an empty directory."
[ "$(count_androidtest_cases "$tmp/does-not-exist")" = "0" ] ||
    fail "count_androidtest_cases reported non-zero for a missing directory."

cat >"$tmp/TEST-a.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="a" tests="3" failures="0" errors="0" skipped="0"></testsuite>
XML
cat >"$tmp/TEST-b.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="b" tests="2" failures="0" errors="0" skipped="0"></testsuite>
XML
got="$(count_androidtest_cases "$tmp")"
[ "$got" = "5" ] ||
    fail "count_androidtest_cases summed to '$got', expected 5 across two suites."

# A results tree that exists but holds no <testsuite tests=> lines is zero.
rm -f "$tmp"/TEST-*.xml
echo '<other/>' >"$tmp/TEST-empty.xml"
[ "$(count_androidtest_cases "$tmp")" = "0" ] ||
    fail "count_androidtest_cases counted a file with no testsuite tests attribute."

# --- 2. workload correctness lane fails on an outer-timeout kill -------------
[ -f "$WORKLOAD_RUNNER" ] || fail "missing $WORKLOAD_RUNNER"
# The 124/137 arm must consult the tolerate flag and return the status when not
# tolerated (rather than an unconditional return 0).
awk '
    /^        124 \| 137\)/ { inarm = 1 }
    inarm && /fixture_timeout_tolerated/ { saw_flag = 1 }
    inarm && /return "\$status"/ { saw_return = 1 }
    inarm && /;;/ { inarm = 0 }
    END { exit (saw_flag && saw_return) ? 0 : 1 }
' "$WORKLOAD_RUNNER" ||
    fail "run_workload_regressions.sh no longer fails the correctness lane on a 124/137 outer-timeout kill."

# The exit-3 (bounded-stop) arm must return the status when the broad PGO flag
# is not set, rather than an unconditional return 0 that hid a stop regression.
awk -v code='"\\$HOST_WORKLOAD_STOP_TIMEOUT_EXIT_CODE"\\)' '
    $0 ~ code { inarm = 1 }
    inarm && /HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE/ { saw_flag = 1 }
    inarm && /return "\$status"/ { saw_return = 1 }
    inarm && /;;/ && seen { inarm = 0 }
    inarm { seen = 1 }
    END { exit (saw_flag && saw_return) ? 0 : 1 }
' "$WORKLOAD_RUNNER" ||
    fail "run_workload_regressions.sh no longer fails the correctness lane on a bounded-stop failure (exit 3)."

# --- 3. mutation spot-check compiles before counting a kill, scores from XML --
[ -f "$MUTATION" ] || fail "missing $MUTATION"
grep -q 'compileReleaseKotlin' "$MUTATION" ||
    fail "mutation_spot_check.sh no longer compiles the mutant before running tests (a non-compiling mutant would be a false kill)."
# The kill verdict must come from the JUnit results XML, not the raw Gradle exit
# code, or a renamed/deleted test class ("No tests found" -> non-zero exit) would
# be counted as a phantom kill.
grep -q 'test-results/testReleaseUnitTest' "$MUTATION" ||
    fail "mutation_spot_check.sh no longer scores the mutant from the JUnit results XML (a vanished test class would be a phantom kill)."
grep -q 'NOTESTS' "$MUTATION" ||
    fail "mutation_spot_check.sh no longer distinguishes a zero-test run from a real kill."

echo "OK: test-integrity guards (androidTest count, workload timeout+stop, mutant compile+XML score) are in place."
