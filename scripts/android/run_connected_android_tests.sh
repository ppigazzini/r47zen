#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
DEFAULTS_PATH="$ANDROID_DIR/r47-defaults.properties"
LOG_DIR="$PROJECT_ROOT/ci-artifacts/logs"
PROGRAM_FIXTURE_TEST_CLASS="io.github.ppigazzini.r47zen.ProgramFixtureInstrumentedTest"
R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT="${R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT:-6m}"
R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER="${R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER:-30s}"
R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT_SIGNAL="${R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT_SIGNAL:-TERM}"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

is_truthy() {
    case "${1:-}" in
        1|true|TRUE|yes|YES|on|ON)
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

emit_fixture_timeout_warning() {
    local fixture="$1"
    local reason="$2"
    local message="$fixture did not finish within the Android fixture budget (${reason}); the connected-test safety net killed the selection and continued with degraded coverage for this fixture."

    echo "WARNING: $message" >&2

    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::warning title=Android PROGRAMS fixture timeout::$message"
    fi

    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        printf '%s\n' "- Warning: $message" >> "$GITHUB_STEP_SUMMARY"
    fi
}

cleanup_connected_test_processes() {
    if ! command -v adb >/dev/null 2>&1; then
        return 0
    fi

    adb shell am force-stop "$R47_DEBUG_TEST_APPLICATION_ID" >/dev/null 2>&1 || true
    adb shell am force-stop "$R47_DEBUG_APPLICATION_ID" >/dev/null 2>&1 || true
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
        :app:connectedDebugAndroidTest
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
        "-Pr47.coreVersion=$R47_CONNECTED_ANDROID_TEST_CORE_VERSION"
        "-Pr47.sourceRepositoryUrl=$R47_CONNECTED_ANDROID_TEST_SOURCE_REPOSITORY_URL"
        "-Pr47.sourceCommit=$R47_CONNECTED_ANDROID_TEST_SOURCE_COMMIT"
        "-Pr47.upstreamSourceRepositoryUrl=$R47_CONNECTED_ANDROID_TEST_UPSTREAM_SOURCE_REPOSITORY_URL"
        "-Pr47.upstreamSourceCommit=$R47_CONNECTED_ANDROID_TEST_UPSTREAM_SOURCE_COMMIT"
        "-Pr47.xlsxioSourceRepositoryUrl=$R47_CONNECTED_ANDROID_TEST_XLSXIO_SOURCE_REPOSITORY_URL"
        "-Pr47.xlsxioSourceCommit=$R47_CONNECTED_ANDROID_TEST_XLSXIO_SOURCE_COMMIT"
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
R47_ANDROID_APPLICATION_ID="$(read_default_property R47_DEFAULT_ANDROID_APPLICATION_ID)"
R47_DEBUG_APPLICATION_ID="${R47_ANDROID_APPLICATION_ID}.debug"
R47_DEBUG_TEST_APPLICATION_ID="${R47_DEBUG_APPLICATION_ID}.test"

TEST_SELECTION_SPECS=(
    "FactorsInstrumentedTest|io.github.ppigazzini.r47zen.FactorsInstrumentedTest||"
    "DisplayLifecycleInstrumentedTest|io.github.ppigazzini.r47zen.DisplayLifecycleInstrumentedTest||"
    "GraphRedrawInstrumentedTest|io.github.ppigazzini.r47zen.GraphRedrawInstrumentedTest||"
    "StorageAccessCoordinatorInstrumentedTest|io.github.ppigazzini.r47zen.StorageAccessCoordinatorInstrumentedTest||"
    "PROGRAMS/BinetV3.p47|${PROGRAM_FIXTURE_TEST_CLASS}#loadAndRunBinetV3ThroughAndroidRuntime|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
    "PROGRAMS/GudrmPL.p47|${PROGRAM_FIXTURE_TEST_CLASS}#loadAndRunGudrmPLThroughAndroidRuntime|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
    "PROGRAMS/MANSLV2.p47|${PROGRAM_FIXTURE_TEST_CLASS}#loadAndRunMANSLV2ThroughAndroidRuntime|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
    "PROGRAMS/NQueens.p47|${PROGRAM_FIXTURE_TEST_CLASS}#loadAndRunNQueensThroughAndroidRuntime|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
    "PROGRAMS/SPIRALk.p47|${PROGRAM_FIXTURE_TEST_CLASS}#loadAndRunSPIRALkThroughAndroidRuntime|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
)

cd "$ANDROID_DIR"

for selection_spec in "${TEST_SELECTION_SPECS[@]}"; do
    IFS='|' read -r selection_name selection_filter timeout_duration kill_after <<< "$selection_spec"
    log_file="$LOG_DIR/android-connected-$(sanitize_label "$selection_name").log"

    if run_connected_selection "$selection_name" "$selection_filter" "$log_file" "$timeout_duration" "$kill_after"; then
        continue
    else
        status=$?
    fi

    case "$status" in
        124|137)
            emit_fixture_timeout_warning "$selection_name" "the outer timeout had to stop the hung connected-test selection"
            cleanup_connected_test_processes
            ;;
        *)
            fail "Connected Android test selection $selection_name failed with exit status $status. See $log_file."
            ;;
    esac
done
