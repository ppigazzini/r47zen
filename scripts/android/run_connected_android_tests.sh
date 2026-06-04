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
NON_FIXTURE_TEST_CLASSES=(
    "io.github.ppigazzini.r47zen.FactorsInstrumentedTest"
    "io.github.ppigazzini.r47zen.DisplayLifecycleInstrumentedTest"
    "io.github.ppigazzini.r47zen.GraphRedrawInstrumentedTest"
    "io.github.ppigazzini.r47zen.StorageAccessCoordinatorInstrumentedTest"
)
REQUIRED_CONNECTED_ANDROID_SELECTIONS=(
    "ProgramFixtureInstrumentation"
)

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

NON_FIXTURE_TEST_FILTER="$(
    IFS=,
    printf '%s' "${NON_FIXTURE_TEST_CLASSES[*]}"
)"

TEST_SELECTION_SPECS=(
    "NonFixtureInstrumentation|$NON_FIXTURE_TEST_FILTER||"
    "ProgramFixtureInstrumentation|${PROGRAM_FIXTURE_TEST_CLASS}|$R47_CONNECTED_ANDROID_FIXTURE_TIMEOUT|$R47_CONNECTED_ANDROID_FIXTURE_KILL_AFTER"
)

cd "$ANDROID_DIR"

for selection_spec in "${TEST_SELECTION_SPECS[@]}"; do
    IFS='|' read -r selection_name selection_filter timeout_duration kill_after <<<"$selection_spec"
    log_file="$LOG_DIR/android-connected-$(sanitize_label "$selection_name").log"

    if run_connected_selection "$selection_name" "$selection_filter" "$log_file" "$timeout_duration" "$kill_after"; then
        continue
    else
        status=$?
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
