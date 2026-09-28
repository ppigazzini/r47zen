#!/bin/bash

# Contract: the test-integrity guards stay in place, so a test lane cannot
# silently pass having tested nothing.
#
# 1. count_androidtest_cases (scripts/lib/common.sh) is exercised functionally
#    against synthetic JUnit result XML: it must sum <testsuite tests="N">
#    counts and report 0 for an empty or missing directory. The connected lane
#    fails a selection that reports success with 0 executed cases.
# 2. The host workload runner's fixture-exit policy is run, not read: the
#    contract sources run_workload_regressions.sh (its main guard builds
#    nothing) and drives run_host_workload_fixture with a stub timeout that
#    exits 124, 137, 3, 1, or 0. With every tolerate variable unset, as the
#    correctness lane runs it, a hang, a wrong result, and a bounded-stop
#    failure each fail; each tolerate variable widens only what it names.
# 3. The mutation spot-check is run, not read: the contract runs
#    mutation_spot_check.sh against a sandbox that holds copies of the seam
#    sources and a fake gradlew. It must kill every mutant when the tests fail,
#    fail when a mutant survives, when a test class runs zero tests, when no
#    results XML appears, and when a mutant does not compile; it must compile
#    each mutant before scoring it, and must restore every seam source.
# 4. The connected-test runner's guards are run, not read: the contract runs
#    run_connected_android_tests.sh against a sandbox with a fake gradlew and
#    adb that write AGP-shaped result XML and, like connectedReleaseAndroidTest,
#    run only the first class of a comma-joined filter. Every class must run in
#    a selection of its own and report results for exactly that class; a later
#    selection that runs nothing fails even after earlier ones ran tests; a
#    stale result file never counts; each selection's results survive for the
#    artifact upload.
#
# Pure-host check: no SDK, no staged native tree, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

WORKLOAD_RUNNER="$PROJECT_ROOT/scripts/workload-regressions/run_workload_regressions.sh"
MUTATION="$PROJECT_ROOT/scripts/android/mutation_spot_check.sh"
CONNECTED="$PROJECT_ROOT/scripts/android/run_connected_android_tests.sh"

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

# AGP's shape: a <testsuites> wrapper that repeats the total, and suite and
# class names that carry digits of their own (r47zen). Only the suite's tests
# attribute counts.
rm -f "$tmp"/TEST-*.xml
cat >"$tmp/TEST-agp.xml" <<'XML'
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="3" failures="0" errors="0" skipped="0" time="0.000">
  <testsuite name="io.github.ppigazzini.r47zen.Sample2Test" tests="3" failures="0" errors="0" skipped="0" time="1.5">
    <testcase name="a1" classname="io.github.ppigazzini.r47zen.Sample2Test" time="0.5" />
    <testcase name="a2" classname="io.github.ppigazzini.r47zen.Sample2Test" time="0.5" />
    <testcase name="a3" classname="io.github.ppigazzini.r47zen.Sample2Test" time="0.5" />
  </testsuite>
</testsuites>
XML
got="$(count_androidtest_cases "$tmp")"
[ "$got" = "3" ] ||
    fail "count_androidtest_cases counted '$got' in AGP-shaped XML holding 3 tests."
got="$(androidtest_classes_run "$tmp")"
[ "$got" = "io.github.ppigazzini.r47zen.Sample2Test" ] ||
    fail "androidtest_classes_run reported '$got' for a single-class result."

# A results tree that exists but holds no <testsuite tests=> lines is zero.
rm -f "$tmp"/TEST-*.xml
echo '<other/>' >"$tmp/TEST-empty.xml"
[ "$(count_androidtest_cases "$tmp")" = "0" ] ||
    fail "count_androidtest_cases counted a file with no testsuite tests attribute."

# --- 2. workload runner fixture-exit policy, run against a stub timeout -------
[ -f "$WORKLOAD_RUNNER" ] || fail "missing $WORKLOAD_RUNNER"
stub_timeout="$tmp/stub-timeout"
printf '#!/bin/bash\nexit "${STUB_EXIT:?}"\n' >"$stub_timeout"
chmod +x "$stub_timeout"

# Print the status run_host_workload_fixture returns for FIXTURE when the
# fixture process exits STUB_STATUS, under the NAME=VALUE tolerate settings
# that follow. Everything else starts unset, as in the correctness lane.
fixture_status() {
    local fixture="$1" stub_status="$2"
    shift 2
    (
        unset HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE HOST_WORKLOAD_TOLERATE_TIMEOUT \
            HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES GITHUB_ACTIONS GITHUB_STEP_SUMMARY
        local setting
        for setting in "$@"; do
            export "${setting?}"
        done
        # shellcheck source=scripts/workload-regressions/run_workload_regressions.sh
        source "$WORKLOAD_RUNNER"
        export STUB_EXIT="$stub_status"
        local status=0
        run_host_workload_fixture "$fixture" "$stub_timeout" 1s 1s 2>/dev/null || status=$?
        echo "$status"
    )
}

# expect_fixture_status LABEL pass|fail FIXTURE STUB_STATUS [NAME=VALUE...]
expect_fixture_status() {
    local label="$1" want="$2"
    shift 2
    local got
    got="$(fixture_status "$@")"
    if [ "$want" = pass ] && [ "$got" != 0 ]; then
        fail "workload runner: $label returned $got, expected success."
    fi
    if [ "$want" = fail ] && [ "$got" = 0 ]; then
        fail "workload runner: $label returned success, expected a failure."
    fi
}

# The runner's fixture list and the harness's scenario table must name the same
# fixtures; the runner checks this before it builds, and so does this contract.
fixture_gaps="$(
    # shellcheck source=scripts/workload-regressions/run_workload_regressions.sh
    source "$WORKLOAD_RUNNER"
    program_fixture_list_gaps
)"
[ -z "$fixture_gaps" ] || fail "workload runner and harness name different fixtures: $fixture_gaps"

expect_fixture_status "a clean fixture" pass NQueens.p47 0
expect_fixture_status "an outer-timeout kill (124)" fail NQueens.p47 124
expect_fixture_status "an outer-timeout kill (137)" fail NQueens.p47 137
expect_fixture_status "a wrong result" fail NQueens.p47 1
expect_fixture_status "a bounded-stop failure (exit 3)" fail MANSLV2.p47 3
expect_fixture_status "HOST_WORKLOAD_TOLERATE_TIMEOUT on a timeout" pass \
    NQueens.p47 124 HOST_WORKLOAD_TOLERATE_TIMEOUT=true
expect_fixture_status "HOST_WORKLOAD_TOLERATE_TIMEOUT on a wrong result" fail \
    NQueens.p47 1 HOST_WORKLOAD_TOLERATE_TIMEOUT=true
expect_fixture_status "HOST_WORKLOAD_TOLERATE_TIMEOUT on a bounded-stop failure" fail \
    MANSLV2.p47 3 HOST_WORKLOAD_TOLERATE_TIMEOUT=true
expect_fixture_status "the timeout allowlist on its own fixture" pass \
    SPIRALk.p47 124 HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES=SPIRALk.p47
expect_fixture_status "the timeout allowlist on another fixture" fail \
    NQueens.p47 124 HOST_WORKLOAD_TOLERATE_TIMEOUT_FIXTURES=SPIRALk.p47
expect_fixture_status "HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE on a wrong result" pass \
    NQueens.p47 1 HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE=true
expect_fixture_status "HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE on a bounded-stop failure" pass \
    MANSLV2.p47 3 HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE=true

# --- 3. mutation spot-check, run against a sandbox and a fake gradlew ---------
[ -f "$MUTATION" ] || fail "missing $MUTATION"
seam_rel="app/src/main/java/io/github/ppigazzini/r47zen"
sandbox="$tmp/mutation-sandbox"
mkdir -p "$sandbox/android/$seam_rel" "$sandbox/originals"
for seam in LiveProgramStopKeyPolicy LiveKeyRouter KeypadSnapshot LcdThemePolicy GraphGestureAccumulator; do
    cp "$PROJECT_ROOT/android/$seam_rel/$seam.kt" "$sandbox/android/$seam_rel/"
    cp "$PROJECT_ROOT/android/$seam_rel/$seam.kt" "$sandbox/originals/"
done
# Answer compile calls with FAKE_COMPILE_EXIT and test calls by FAKE_TEST_MODE:
# kill (a failing test), survive (all pass), notests (zero tests ran), or noxml
# (Gradle never wrote results). Every call is logged in order.
cat >"$sandbox/android/gradlew" <<'SH'
#!/bin/bash
printf '%s\n' "$*" >>"$FAKE_GRADLE_LOG"
case " $* " in
    *" :app:compileReleaseKotlin "*) exit "${FAKE_COMPILE_EXIT:-0}" ;;
esac
class=""
previous=""
for arg in "$@"; do
    [ "$previous" = --tests ] && class="$arg"
    previous="$arg"
done
results="app/build/test-results/testReleaseUnitTest"
mkdir -p "$results"
case "$FAKE_TEST_MODE" in
    kill) tests=3 failures=1 ;;
    survive) tests=3 failures=0 ;;
    notests) tests=0 failures=0 ;;
    noxml) exit 1 ;;
esac
printf '<testsuite name="%s" tests="%s" failures="%s" errors="0"/>\n' \
    "$class" "$tests" "$failures" >"$results/TEST-$class.xml"
[ "$failures" = 0 ] && [ "$tests" != 0 ]
SH
chmod +x "$sandbox/android/gradlew"

# run_mutation OUTPUT_FILE TEST_MODE [COMPILE_EXIT]: run the real spot-check in
# the sandbox, return its exit status, and require every seam source restored.
run_mutation() {
    local output="$1" mode="$2" compile_exit="${3:-0}" status=0
    : >"$sandbox/gradle.log"
    R47_MUTATION_ANDROID_DIR="$sandbox/android" FAKE_GRADLE_LOG="$sandbox/gradle.log" \
        FAKE_TEST_MODE="$mode" FAKE_COMPILE_EXIT="$compile_exit" \
        bash "$MUTATION" >"$output" 2>&1 || status=$?
    local seam
    for seam in "$sandbox/originals"/*.kt; do
        cmp -s "$seam" "$sandbox/android/$seam_rel/$(basename "$seam")" ||
            fail "mutation spot-check left $(basename "$seam") mutated (mode $mode)."
    done
    return "$status"
}

run_mutation "$tmp/mut-kill.log" kill ||
    fail "mutation spot-check failed with every mutant killed: $(tail -n 3 "$tmp/mut-kill.log")"
grep -Eq '^Mutation spot-check: ([0-9]+)/\1 mutants killed\.' "$tmp/mut-kill.log" ||
    fail "mutation spot-check did not report every mutant killed."
# Each mutant must compile before its tests run: the log alternates compile,
# test, compile, test, and has a test call for every compile.
awk '
    /:app:compileReleaseKotlin/ { compiled++; if (pending) bad = 1; pending = 1; next }
    /:app:testReleaseUnitTest/ { tested++; if (!pending) bad = 1; pending = 0 }
    END { exit (bad || compiled == 0 || compiled != tested) ? 1 : 0 }
' "$sandbox/gradle.log" ||
    fail "mutation spot-check did not compile each mutant before scoring it."

expect_mutation_failure() {
    local label="$1" pattern="$2"
    shift 2
    if run_mutation "$tmp/mut-case.log" "$@"; then
        fail "mutation spot-check passed $label."
    fi
    grep -q -- "$pattern" "$tmp/mut-case.log" ||
        fail "mutation spot-check failed $label without the expected diagnosis: $(tail -n 2 "$tmp/mut-case.log")"
}

expect_mutation_failure "a surviving mutant" "survived" survive
expect_mutation_failure "a zero-test run" "executed zero tests" notests
expect_mutation_failure "a run with no results XML" "no JUnit results" noxml
expect_mutation_failure "a mutant that does not compile" "did not compile" kill 1

# --- 4. connected-test selections, run against a sandbox and a fake gradlew --
[ -f "$CONNECTED" ] || fail "missing $CONNECTED"
connected_sandbox="$tmp/connected-sandbox"
connected_results="$connected_sandbox/android/app/build/outputs/androidTest-results"
mkdir -p "$connected_sandbox/android" "$connected_sandbox/bin"
cp "$PROJECT_ROOT/android/r47-defaults.properties" "$connected_sandbox/android/"
printf '#!/bin/bash\nexit 0\n' >"$connected_sandbox/bin/adb"
# Run the first class of the filter, as connectedReleaseAndroidTest does with a
# comma-joined list, and write two AGP-shaped results for it. The class that
# FAKE_EMPTY_SELECTION names runs nothing; for the one FAKE_WRONG_SELECTION
# names, the results report another class.
cat >"$connected_sandbox/android/gradlew" <<'SH'
#!/bin/bash
filter=""
for arg in "$@"; do
    case "$arg" in
        -Pandroid.testInstrumentationRunnerArguments.class=*) filter="${arg#*=}" ;;
    esac
done
ran="${filter%%,*}"
case "$ran" in
    *".${FAKE_EMPTY_SELECTION:-none}") exit 0 ;;
    *".${FAKE_WRONG_SELECTION:-none}") ran="$ran.Other" ;;
esac
results="app/build/outputs/androidTest-results/connected/release"
mkdir -p "$results"
cat >"$results/TEST-test(AVD) - 16.xml" <<XML
<testsuites tests="2"><testsuite name="$ran" tests="2">
<testcase name="t1" classname="$ran" /><testcase name="t2" classname="$ran" />
</testsuite></testsuites>
XML
SH
chmod +x "$connected_sandbox/bin/adb" "$connected_sandbox/android/gradlew"

# run_connected OUTPUT_FILE [NAME=VALUE...]: run the real runner in the sandbox
# with the fake settings that follow, and return its exit status.
run_connected() {
    local output="$1" status=0
    shift
    (
        export PATH="$connected_sandbox/bin:$PATH"
        export R47_CONNECTED_ANDROID_DIR="$connected_sandbox/android"
        export R47_CONNECTED_ANDROID_LOG_DIR="$tmp/connected-logs"
        unset GITHUB_ACTIONS GITHUB_STEP_SUMMARY FAKE_EMPTY_SELECTION FAKE_WRONG_SELECTION
        local setting name
        for setting in "$@"; do
            export "${setting?}"
        done
        for name in JOBS NDK_VERSION ABI_FILTERS CORE_COMMIT SOURCE_REPOSITORY_URL SOURCE_COMMIT \
            UPSTREAM_SOURCE_REPOSITORY_URL UPSTREAM_SOURCE_COMMIT XLSXIO_SOURCE_REPOSITORY_URL \
            XLSXIO_SOURCE_COMMIT; do
            export "R47_CONNECTED_ANDROID_TEST_$name=contract"
        done
        bash "$CONNECTED"
    ) >"$output" 2>&1 || status=$?
    return "$status"
}

# expect_connected_failure LABEL PATTERN [NAME=VALUE...]
expect_connected_failure() {
    local label="$1" pattern="$2"
    shift 2
    if run_connected "$tmp/connected-case.log" "$@"; then
        fail "connected runner passed $label."
    fi
    grep -q -- "$pattern" "$tmp/connected-case.log" ||
        fail "connected runner failed $label without the expected diagnosis: $(tail -n 2 "$tmp/connected-case.log")"
}

run_connected "$tmp/connected-all.log" ||
    fail "connected runner failed with every class running: $(tail -n 3 "$tmp/connected-all.log")"
mapfile -t connected_classes < <(sed -n '/^NON_FIXTURE_TEST_CLASSES=(/,/^)/p' "$CONNECTED" |
    sed -n 's/^[[:space:]]*"\(io\.github\.[^"]*\)"$/\1/p')
[ "${#connected_classes[@]}" -gt 0 ] || fail "could not read NON_FIXTURE_TEST_CLASSES from $CONNECTED"
connected_classes+=("io.github.ppigazzini.r47zen.ProgramFixtureInstrumentedTest")
for class in "${connected_classes[@]}"; do
    grep -q "executed 2 test case(s) of $class\$" "$tmp/connected-all.log" ||
        fail "connected runner never ran $class in a selection of its own: $(grep -c 'executed' "$tmp/connected-all.log") selection(s) reported."
done
for selection in "$connected_results"/selections/*/; do
    [ "$(count_androidtest_cases "$selection")" = 2 ] ||
        fail "connected runner did not keep the results of $(basename "$selection") for the artifact upload."
done

expect_connected_failure "a later selection that ran nothing, after earlier ones ran tests" \
    "ProgramFixtureInstrumentation reported success but executed 0 tests" \
    FAKE_EMPTY_SELECTION=ProgramFixtureInstrumentedTest
expect_connected_failure "a selection whose results report another class" \
    "GraphRedrawInstrumentedTest asked for io.github.ppigazzini.r47zen.GraphRedrawInstrumentedTest" \
    FAKE_WRONG_SELECTION=GraphRedrawInstrumentedTest

mkdir -p "$connected_results/connected"
printf '<testsuite name="stale" tests="5"/>\n' >"$connected_results/connected/TEST-stale.xml"
expect_connected_failure "a stale result file for a first selection that ran nothing" \
    "FactorsInstrumentedTest reported success but executed 0 tests" \
    FAKE_EMPTY_SELECTION=FactorsInstrumentedTest

echo "OK: test-integrity guards hold when run: androidTest count, workload fixture-exit policy, mutation spot-check scoring, one connected selection per class."
