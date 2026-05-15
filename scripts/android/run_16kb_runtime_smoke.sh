#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
DEFAULTS_PATH="$ANDROID_DIR/r47-defaults.properties"
DEFAULT_TEST_CLASS="io.github.ppigazzini.r47zen.DisplayLifecycleInstrumentedTest#activityRecreationPreservesSpiralkGraphSnapshot"

usage() {
    cat <<'EOF'
Usage:
    scripts/android/run_16kb_runtime_smoke.sh [--serial <adb-serial>] [--abi-filters <abi[,abi...]>] [--test-class <class[#method]>]

Runs the focused Android runtime smoke lane on a connected 16 KB page-size
device or emulator.

Options:
  --serial       Use a specific connected adb target. Required when multiple devices are attached.
  --abi-filters  Override Gradle abi filters. Defaults to R47_DEFAULT_ANDROID_TEST_ABI_FILTERS.
  --test-class   Override the instrumentation class or class#method to run.
  -h, --help     Show this help text.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

require_prerequisites() {
    command -v adb >/dev/null 2>&1 || fail "adb is required on PATH."

    if [[ ! -f "$DEFAULTS_PATH" ]]; then
        fail "Missing Android defaults file: $DEFAULTS_PATH"
    fi

    if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f "$ANDROID_DIR/local.properties" ]]; then
        fail "Android SDK is not discoverable. Set ANDROID_HOME or ANDROID_SDK_ROOT, or provide android/local.properties."
    fi
}

list_connected_devices() {
    adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

resolve_serial() {
    local requested_serial="$1"

    if [[ -n "$requested_serial" ]]; then
        adb -s "$requested_serial" get-state >/dev/null 2>&1 || \
            fail "adb target $requested_serial is not available."
        printf '%s\n' "$requested_serial"
        return 0
    fi

    mapfile -t connected_devices < <(list_connected_devices)

    case "${#connected_devices[@]}" in
        0)
            fail "No connected Android device or emulator found."
            ;;
        1)
            printf '%s\n' "${connected_devices[0]}"
            ;;
        *)
            fail "Multiple Android devices are attached. Pass --serial <adb-serial>."
            ;;
    esac
}

read_page_size_bytes() {
    local serial="$1"
    local page_size=""

    page_size="$(adb -s "$serial" shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r' | tail -n1)"
    if [[ -z "$page_size" ]]; then
        page_size="$(adb -s "$serial" shell getconf PAGESIZE 2>/dev/null | tr -d '\r' | tail -n1)"
    fi

    [[ -n "$page_size" ]] || \
        fail "Unable to read page size from $serial. Expected getconf PAGE_SIZE or PAGESIZE on the target shell."

    printf '%s\n' "$page_size"
}

main() {
    local requested_serial=""
    local abi_filters=""
    local test_class="$DEFAULT_TEST_CLASS"
    local serial=""
    local page_size=""

    while [[ "$#" -gt 0 ]]; do
        case "$1" in
            --serial)
                shift
                [[ "$#" -gt 0 ]] || fail "Missing value for --serial"
                requested_serial="$1"
                ;;
            --abi-filters)
                shift
                [[ "$#" -gt 0 ]] || fail "Missing value for --abi-filters"
                abi_filters="$1"
                ;;
            --test-class)
                shift
                [[ "$#" -gt 0 ]] || fail "Missing value for --test-class"
                test_class="$1"
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown option: $1"
                ;;
        esac
        shift
    done

    require_prerequisites
    # shellcheck disable=SC1090
    . "$DEFAULTS_PATH"

    serial="$(resolve_serial "$requested_serial")"
    page_size="$(read_page_size_bytes "$serial")"
    [[ "$page_size" == "16384" ]] || \
        fail "Connected target $serial reports page size $page_size bytes. This smoke lane requires 16384-byte pages."

    if [[ -z "$abi_filters" ]]; then
        abi_filters="$R47_DEFAULT_ANDROID_TEST_ABI_FILTERS"
    fi

    echo "Running 16 KB runtime smoke on $serial (page size ${page_size} bytes, abi filters $abi_filters)."
    echo "Focused instrumentation target: $test_class"

    cd "$ANDROID_DIR"
    ANDROID_SERIAL="$serial" ./gradlew \
        :app:connectedDebugAndroidTest \
        --no-daemon --stacktrace --console=plain \
        "-Pr47.abiFilters=$abi_filters" \
        "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
}

main "$@"
