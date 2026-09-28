#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
# Both directories are overridable so run_test_integrity_contract.sh can run
# this script against a sandbox with a fake gradlew.
ANDROID_DIR="${R47_CONNECTED_ANDROID_DIR:-$PROJECT_ROOT/android}"
DEFAULTS_PATH="$ANDROID_DIR/r47-defaults.properties"
LOG_DIR="${R47_CONNECTED_ANDROID_LOG_DIR:-$PROJECT_ROOT/ci-artifacts/logs}"
CONNECTED_RESULTS_DIR="$ANDROID_DIR/app/build/outputs/androidTest-results/connected"
SELECTION_RESULTS_ROOT="$ANDROID_DIR/app/build/outputs/androidTest-results/selections"
PROGRAM_FIXTURE_TEST_CLASS="io.github.ppigazzini.r47zen.ProgramFixtureInstrumentedTest"
R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT="${R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT:-6m}"
R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER="${R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER:-30s}"
R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT_SIGNAL="${R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT_SIGNAL:-TERM}"
# Each class runs as a selection of its own. A comma-joined -e class list ran
# only its first class under connectedReleaseAndroidTest, so one filter names
# one class, and each selection must report results for exactly that class.
NON_FIXTURE_TEST_CLASSES=(
    "io.github.ppigazzini.r47zen.FactorsInstrumentedTest"
    "io.github.ppigazzini.r47zen.DisplayLifecycleInstrumentedTest"
    "io.github.ppigazzini.r47zen.GraphRedrawInstrumentedTest"
    "io.github.ppigazzini.r47zen.GraphTouchStressInstrumentedTest"
    "io.github.ppigazzini.r47zen.StorageAccessCoordinatorInstrumentedTest"
    "io.github.ppigazzini.r47zen.SystemBarInsetsInstrumentedTest"
)
# Every connected selection is required: a timeout in any one fails the Android
# lane, never downgrades to a warning. The list is filled from the selection
# specs below. emit_fixture_timeout_warning is intentionally retained for any
# future non-required, timed selection.
REQUIRED_CONNECTED_ANDROID_SELECTIONS=()

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

is_truthy() {
    case "${1:-}" in
        1 | true | TRUE | yes | YES | on | ON)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

require_env() {
    local name="$1"

    if [[ -z "${!name:-}" ]]; then
        fail "Missing required environment variable $name"
    fi
}

resolve_timeout_bin() {
    local requested_bin="${R47_CONNECTED_ANDROID_TIMEOUT_BIN:-}"

    if [[ -n "$requested_bin" ]]; then
        printf '%s\n' "$requested_bin"
        return 0
    fi

    if command -v timeout >/dev/null 2>&1; then
        printf '%s\n' timeout
        return 0
    fi

    if command -v gtimeout >/dev/null 2>&1; then
        printf '%s\n' gtimeout
        return 0
    fi

    return 1
}

read_default_property() {
    local key="$1"
    local line

    line="$(grep -E "^${key}=" "$DEFAULTS_PATH" | head -n 1 || true)"
    [[ -n "$line" ]] || fail "Missing $key in $DEFAULTS_PATH"
    printf '%s\n' "${line#*=}"
}

sanitize_label() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' '-'
}

selection_requires_timeout_failure() {
    local selection_name="$1"
    local required_selection

    for required_selection in "${REQUIRED_CONNECTED_ANDROID_SELECTIONS[@]}"; do
        if [[ "$selection_name" == "$required_selection" ]]; then
            return 0
        fi
    done

    return 1
}

emit_fixture_timeout_warning() {
    local selection="$1"
    local reason="$2"
    local message="$selection did not finish within the Android connected-test budget (${reason}); the connected-test safety net killed that grouped selection and continued with degraded coverage."

    echo "WARNING: $message" >&2

    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::warning title=Android connected-test selection timeout::$message"
    fi

    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        printf '%s\n' "- Warning: $message" >>"$GITHUB_STEP_SUMMARY"
    fi
}

emit_required_fixture_timeout_error() {
    local selection="$1"
    local reason="$2"
    local log_file="$3"
    local message="$selection did not finish within the Android connected-test budget (${reason}); this required connected-test selection now fails the Android lane. See $log_file."

    echo "ERROR: $message" >&2

    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::error title=Required Android connected-test selection timeout::$message"
    fi

    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        printf '%s\n' "- Error: $message" >>"$GITHUB_STEP_SUMMARY"
    fi
}

cleanup_connected_test_processes() {
    if ! command -v adb >/dev/null 2>&1; then
        return 0
    fi

    adb shell am force-stop "$R47_CONNECTED_ANDROID_TEST_APPLICATION_ID" >/dev/null 2>&1 || true
    adb shell am force-stop "$R47_CONNECTED_ANDROID_APPLICATION_ID" >/dev/null 2>&1 || true
}

run_connected_selection() {
    local selection_name="$1"
    local selection_filter="$2"
    local log_file="$3"
    local timeout_duration="$4"
    local kill_after="$5"
    local status=0
    local gradle_args=(
        --max-workers "$R47_CONNECTED_ANDROID_TEST_JOBS"
        "$R47_CONNECTED_ANDROID_TASK"
    )

    if is_truthy "${R47_CONNECTED_ANDROID_USE_DAEMON:-}"; then
        gradle_args+=(--daemon)
    else
        gradle_args+=(--no-daemon)
    fi

    gradle_args+=(
        --stacktrace
        --console=plain
    )

    if is_truthy "${R47_CONNECTED_ANDROID_ENABLE_CONFIGURATION_CACHE:-}"; then
        gradle_args+=(--configuration-cache)
    fi

    gradle_args+=(
        "-Pr47.ndkVersion=$R47_CONNECTED_ANDROID_TEST_NDK_VERSION"
        "-Pr47.abiFilters=$R47_CONNECTED_ANDROID_TEST_ABI_FILTERS"
        "-Pr47.releaseMinify=$R47_CONNECTED_ANDROID_TEST_RELEASE_MINIFY"
        "-Pr47.releaseShrinkResources=$R47_CONNECTED_ANDROID_TEST_RELEASE_SHRINK_RESOURCES"
        "-Pr47.coreVersion=$R47_CONNECTED_ANDROID_TEST_CORE_VERSION"
        "-Pr47.releaseChannel=$R47_CONNECTED_ANDROID_TEST_RELEASE_CHANNEL"
        "-Pr47.testBuildType=$R47_CONNECTED_ANDROID_TEST_BUILD_TYPE"
        "-Pr47.sourceRepositoryUrl=$R47_CONNECTED_ANDROID_TEST_SOURCE_REPOSITORY_URL"
        "-Pr47.sourceCommit=$R47_CONNECTED_ANDROID_TEST_SOURCE_COMMIT"
        "-Pr47.upstreamSourceRepositoryUrl=$R47_CONNECTED_ANDROID_TEST_UPSTREAM_SOURCE_REPOSITORY_URL"
        "-Pr47.upstreamSourceCommit=$R47_CONNECTED_ANDROID_TEST_UPSTREAM_SOURCE_COMMIT"
        "-Pr47.xlsxioSourceRepositoryUrl=$R47_CONNECTED_ANDROID_TEST_XLSXIO_SOURCE_REPOSITORY_URL"
        "-Pr47.xlsxioSourceCommit=$R47_CONNECTED_ANDROID_TEST_XLSXIO_SOURCE_COMMIT"
        # Instrumentation opt-in: the program-load bridge is excluded by default
        # (it must not ship to users); the connected suite needs its native entry
        # points, so the androidTest build here re-enables it.
        "-Pr47.includeProgramLoadTestBridge=true"
        "-Pandroid.testInstrumentationRunnerArguments.class=$selection_filter"
    )

    echo "INFO: Running connected Android test selection $selection_name" >&2

    mkdir -p "$LOG_DIR"
    cleanup_connected_test_processes

    set +e
    if [[ -n "$timeout_duration" ]]; then
        "$TIMEOUT_BIN" \
            --verbose \
            --signal="$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT_SIGNAL" \
            --kill-after="$kill_after" \
            "$timeout_duration" \
            ./gradlew "${gradle_args[@]}" 2>&1 | tee "$log_file"
    else
        ./gradlew "${gradle_args[@]}" 2>&1 | tee "$log_file"
    fi
    status=${PIPESTATUS[0]}
    set -e

    return "$status"
}

require_env R47_CONNECTED_ANDROID_TEST_JOBS
require_env R47_CONNECTED_ANDROID_TEST_NDK_VERSION
require_env R47_CONNECTED_ANDROID_TEST_ABI_FILTERS
require_env R47_CONNECTED_ANDROID_TEST_CORE_COMMIT
require_env R47_CONNECTED_ANDROID_TEST_SOURCE_REPOSITORY_URL
require_env R47_CONNECTED_ANDROID_TEST_SOURCE_COMMIT
require_env R47_CONNECTED_ANDROID_TEST_UPSTREAM_SOURCE_REPOSITORY_URL
require_env R47_CONNECTED_ANDROID_TEST_UPSTREAM_SOURCE_COMMIT
require_env R47_CONNECTED_ANDROID_TEST_XLSXIO_SOURCE_REPOSITORY_URL
require_env R47_CONNECTED_ANDROID_TEST_XLSXIO_SOURCE_COMMIT

TIMEOUT_BIN="$(resolve_timeout_bin)" || fail "Neither timeout nor gtimeout is available on PATH. Install GNU coreutils timeout or set R47_CONNECTED_ANDROID_TIMEOUT_BIN explicitly."

if [[ ! -f "$DEFAULTS_PATH" ]]; then
    fail "Missing defaults file at $DEFAULTS_PATH"
fi

R47_CONNECTED_ANDROID_TEST_CORE_VERSION="$(printf '%.8s' "$R47_CONNECTED_ANDROID_TEST_CORE_COMMIT")"
R47_CONNECTED_ANDROID_TEST_RELEASE_CHANNEL="${R47_CONNECTED_ANDROID_TEST_RELEASE_CHANNEL:-dev}"
R47_CONNECTED_ANDROID_TEST_BUILD_TYPE="${R47_CONNECTED_ANDROID_TEST_BUILD_TYPE:-}"
R47_CONNECTED_ANDROID_TEST_RELEASE_MINIFY="${R47_CONNECTED_ANDROID_TEST_RELEASE_MINIFY:-false}"
R47_CONNECTED_ANDROID_TEST_RELEASE_SHRINK_RESOURCES="${R47_CONNECTED_ANDROID_TEST_RELEASE_SHRINK_RESOURCES:-false}"
if [[ -z "$R47_CONNECTED_ANDROID_TEST_BUILD_TYPE" ]]; then
    if [[ "$R47_CONNECTED_ANDROID_TEST_RELEASE_CHANNEL" == "dev" ]]; then
        R47_CONNECTED_ANDROID_TEST_BUILD_TYPE="release"
    else
        R47_CONNECTED_ANDROID_TEST_BUILD_TYPE="debug"
    fi
fi
R47_ANDROID_APPLICATION_ID="$(read_default_property R47_DEFAULT_ANDROID_APPLICATION_ID)"

case "$R47_CONNECTED_ANDROID_TEST_BUILD_TYPE" in
    debug)
        R47_CONNECTED_ANDROID_TASK=":app:connectedDebugAndroidTest"
        R47_CONNECTED_ANDROID_APPLICATION_ID="${R47_ANDROID_APPLICATION_ID}.debug"
        ;;
    release)
        R47_CONNECTED_ANDROID_TASK=":app:connectedReleaseAndroidTest"
        if [[ "$R47_CONNECTED_ANDROID_TEST_RELEASE_CHANNEL" == "dev" ]]; then
            R47_CONNECTED_ANDROID_APPLICATION_ID="${R47_ANDROID_APPLICATION_ID}.dev"
        else
            R47_CONNECTED_ANDROID_APPLICATION_ID="$R47_ANDROID_APPLICATION_ID"
        fi
        ;;
    *)
        fail "Unsupported R47_CONNECTED_ANDROID_TEST_BUILD_TYPE value: $R47_CONNECTED_ANDROID_TEST_BUILD_TYPE"
        ;;
esac

R47_CONNECTED_ANDROID_TEST_APPLICATION_ID="${R47_CONNECTED_ANDROID_APPLICATION_ID}.test"

TEST_SELECTION_SPECS=()
for test_class in "${NON_FIXTURE_TEST_CLASSES[@]}"; do
    TEST_SELECTION_SPECS+=("${test_class##*.}|$test_class||")
done
TEST_SELECTION_SPECS+=(
    "ProgramFixtureInstrumentation|${PROGRAM_FIXTURE_TEST_CLASS}|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
)
for selection_spec in "${TEST_SELECTION_SPECS[@]}"; do
    REQUIRED_CONNECTED_ANDROID_SELECTIONS+=("${selection_spec%%|*}")
done

cd "$ANDROID_DIR"
rm -rf "$SELECTION_RESULTS_ROOT"

for selection_spec in "${TEST_SELECTION_SPECS[@]}"; do
    IFS='|' read -r selection_name selection_filter timeout_duration kill_after <<<"$selection_spec"
    log_file="$LOG_DIR/android-connected-$(sanitize_label "$selection_name").log"
    selection_results="$SELECTION_RESULTS_ROOT/$(sanitize_label "$selection_name")"

    # Each selection starts from an empty results directory and keeps its own
    # copy under selections/, so the zero-test guard counts what this selection
    # ran, never a result file an earlier selection left behind.
    rm -rf "$CONNECTED_RESULTS_DIR"
    status=0
    run_connected_selection "$selection_name" "$selection_filter" "$log_file" "$timeout_duration" "$kill_after" ||
        status=$?
    if [[ -d "$CONNECTED_RESULTS_DIR" ]]; then
        mkdir -p "$SELECTION_RESULTS_ROOT"
        mv "$CONNECTED_RESULTS_DIR" "$selection_results"
    fi

    if [[ "$status" -eq 0 ]]; then
        # A passing selection must have executed at least one test. A hardcoded
        # -e class filter whose class was renamed or removed can otherwise report
        # success having run nothing, silently shrinking the suite.
        executed="$(count_androidtest_cases "$selection_results")"
        if [[ "$executed" -eq 0 ]]; then
            fail "Connected Android test selection $selection_name reported success but executed 0 tests (check the class filter in NON_FIXTURE_TEST_CLASSES / PROGRAM_FIXTURE_TEST_CLASS). See $log_file."
        fi
        ran_classes="$(androidtest_classes_run "$selection_results")"
        if [[ "$ran_classes" != "$selection_filter" ]]; then
            fail "Connected Android test selection $selection_name asked for $selection_filter but its results report: ${ran_classes:-no class}. See $log_file."
        fi
        echo "INFO: connected selection $selection_name executed $executed test case(s) of $selection_filter" >&2
        continue
    fi

    case "$status" in
        124 | 137)
            cleanup_connected_test_processes
            if selection_requires_timeout_failure "$selection_name"; then
                emit_required_fixture_timeout_error "$selection_name" "the outer timeout had to stop the hung connected-test selection" "$log_file"
                exit 1
            fi
            emit_fixture_timeout_warning "$selection_name" "the outer timeout had to stop the hung connected-test selection"
            ;;
        *)
            fail "Connected Android test selection $selection_name failed with exit status $status. See $log_file."
            ;;
    esac
done
