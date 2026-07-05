#!/bin/bash

# Contract: the test-integrity guards added in REPORT-32 Milestone M3 stay in
# place, so a test lane cannot silently pass having tested nothing.
#
# 1. count_androidtest_cases (scripts/lib/common.sh) is exercised functionally
#    against synthetic JUnit result XML: it must sum <testsuite tests="N">
#    counts and report 0 for an empty or missing directory. The connected lane
#    fails a selection that reports success with 0 executed cases.
# 2. The host workload correctness lane must fail on an outer-timeout kill
#    (124/137) rather than tolerating it unconditionally.
# 3. The mutation spot-check must compile a mutant before running its tests, so
#    a non-compiling mutant is not miscounted as killed.
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
    inarm && /HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE/ { saw_flag = 1 }
    inarm && /return "\$status"/ { saw_return = 1 }
    inarm && /;;/ { inarm = 0 }
    END { exit (saw_flag && saw_return) ? 0 : 1 }
' "$WORKLOAD_RUNNER" ||
    fail "run_workload_regressions.sh no longer fails the correctness lane on a 124/137 outer-timeout kill."

# --- 3. mutation spot-check compiles before counting a kill ------------------
[ -f "$MUTATION" ] || fail "missing $MUTATION"
grep -q 'compileReleaseKotlin' "$MUTATION" ||
    fail "mutation_spot_check.sh no longer compiles the mutant before running tests (a non-compiling mutant would be a false kill)."

echo "OK: test-integrity guards (androidTest count, workload timeout, mutant compile) are in place."
