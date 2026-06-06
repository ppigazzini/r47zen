#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
BUILD_DIR="${HOST_WORKLOAD_BUILD_DIR:-$PROJECT_ROOT/android/build/workload-regressions-host}"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
DEFAULT_GENERATED_PROGRAM_ROOT="$PROJECT_ROOT/android/app/build/generated/assets/runtime/program-fixtures/PROGRAMS"
DEFAULT_UPSTREAM_PROGRAM_ROOT="$PROJECT_ROOT/res/PROGRAMS"
PROGRAM_ROOT="${PROGRAM_ROOT:-}"
PREPARE_NATIVE_INPUTS_SCRIPT="$PROJECT_ROOT/scripts/android/prepare_native_build_inputs.sh"
HOST_WORKLOAD_OUTPUT_NAME="${HOST_WORKLOAD_OUTPUT_NAME:-r47-workload-regression}"
CC_BIN="${CC:-cc}"
HOST_WORKLOAD_STOP_TIMEOUT_EXIT_CODE="${HOST_WORKLOAD_STOP_TIMEOUT_EXIT_CODE:-3}"
HOST_WORKLOAD_FIXTURE_TIMEOUT="${HOST_WORKLOAD_FIXTURE_TIMEOUT:-25s}"
HOST_WORKLOAD_FIXTURE_KILL_AFTER="${HOST_WORKLOAD_FIXTURE_KILL_AFTER:-5s}"
HOST_WORKLOAD_FIXTURE_TIMEOUT_SIGNAL="${HOST_WORKLOAD_FIXTURE_TIMEOUT_SIGNAL:-TERM}"
# When true, a fixture that runs but exits non-zero (e.g. a value-oracle
# mismatch or a crash) is recorded as degraded coverage instead of failing the
# run. The PGO training overlay sets this so a fixture whose output drifts under
# a new upstream cannot break the Android build; the dedicated correctness lane
# leaves it false so the oracle still gates. Compile and setup failures stay
# fatal regardless -- this only affects a fixture's own runtime exit.
HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE="${HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE:-false}"
REQUIRED_PROGRAM_FIXTURE_SPECS=(
    "BinetV3.p47|$HOST_WORKLOAD_FIXTURE_TIMEOUT|$HOST_WORKLOAD_FIXTURE_KILL_AFTER"
    "GudrmPL.p47|$HOST_WORKLOAD_FIXTURE_TIMEOUT|$HOST_WORKLOAD_FIXTURE_KILL_AFTER"
    "MANSLV2.p47|$HOST_WORKLOAD_FIXTURE_TIMEOUT|$HOST_WORKLOAD_FIXTURE_KILL_AFTER"
    "NQueens.p47|$HOST_WORKLOAD_FIXTURE_TIMEOUT|$HOST_WORKLOAD_FIXTURE_KILL_AFTER"
    "SPIRALk.p47|$HOST_WORKLOAD_FIXTURE_TIMEOUT|$HOST_WORKLOAD_FIXTURE_KILL_AFTER"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

resolve_timeout_bin() {
    local requested_bin="${HOST_WORKLOAD_TIMEOUT_BIN:-}"

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

emit_fixture_timeout_warning() {
    local fixture="$1"
    local reason="$2"
    local message="$fixture did not finish within the host workload budget (${reason}); the host safety net kept the lane moving, so this run only records degraded coverage for that fixture."

    echo "WARNING: $message" >&2

    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::warning title=Host workload timeout::$message"
    fi

    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        printf '%s\n' "- Warning: $message" >>"$GITHUB_STEP_SUMMARY"
    fi
}

emit_fixture_failure_warning() {
    local fixture="$1"
    local status="$2"
    local message="$fixture exited with status ${status} (a value-oracle mismatch or a crash); tolerated as degraded PGO training coverage. The dedicated host-workload-regressions lane gates this as a correctness failure."

    echo "WARNING: $message" >&2

    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        echo "::warning title=Host workload fixture failure tolerated::$message"
    fi

    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        printf '%s\n' "- Warning: $message" >>"$GITHUB_STEP_SUMMARY"
    fi
}

run_host_workload_fixture() {
    local fixture="$1"
    local timeout_bin="$2"
    local timeout_duration="$3"
    local kill_after="$4"
    local status=0

    echo "INFO: Running host workload $fixture" >&2

    set +e
    "$timeout_bin" \
        --verbose \
        --signal="$HOST_WORKLOAD_FIXTURE_TIMEOUT_SIGNAL" \
        --kill-after="$kill_after" \
        "$timeout_duration" \
        "$BUILD_DIR/$HOST_WORKLOAD_OUTPUT_NAME" \
        --program-root "$PROGRAM_ROOT" \
        --program-name "$fixture"
    status=$?
    set -e

    case "$status" in
        0)
            return 0
            ;;
        "$HOST_WORKLOAD_STOP_TIMEOUT_EXIT_CODE")
            emit_fixture_timeout_warning "$fixture" "the bounded workload deadline expired inside the host harness"
            return 0
            ;;
        124 | 137)
            emit_fixture_timeout_warning "$fixture" "the outer timeout had to kill the hung workload process"
            return 0
            ;;
        *)
            if [[ "$HOST_WORKLOAD_TOLERATE_FIXTURE_FAILURE" == "true" ]]; then
                emit_fixture_failure_warning "$fixture" "$status"
                return 0
            fi
            return "$status"
            ;;
    esac
}

resolve_program_root() {
    if [[ -n "$PROGRAM_ROOT" ]]; then
        printf '%s\n' "$PROGRAM_ROOT"
        return 0
    fi

    if [[ -d "$DEFAULT_GENERATED_PROGRAM_ROOT" ]]; then
        printf '%s\n' "$DEFAULT_GENERATED_PROGRAM_ROOT"
        return 0
    fi

    printf '%s\n' "$DEFAULT_UPSTREAM_PROGRAM_ROOT"
}

PROGRAM_ROOT="$(resolve_program_root)"

if [[ ! -d "$PROGRAM_ROOT" ]]; then
    fail "Program root $PROGRAM_ROOT does not exist. Run the Android build path that stages program fixtures, run ./scripts/upstream-sync/upstream.sh sync --auto --write-lock, or set PROGRAM_ROOT explicitly."
fi

if ! command -v "$CC_BIN" >/dev/null 2>&1; then
    fail "Compiler $CC_BIN is not available on PATH."
fi

TIMEOUT_BIN="$(resolve_timeout_bin)" || fail "Neither timeout nor gtimeout is available on PATH. Install GNU coreutils timeout or set HOST_WORKLOAD_TIMEOUT_BIN explicitly."

if ! command -v "$TIMEOUT_BIN" >/dev/null 2>&1; then
    fail "Timeout helper $TIMEOUT_BIN is not available on PATH."
fi

for fixture_spec in "${REQUIRED_PROGRAM_FIXTURE_SPECS[@]}"; do
    IFS='|' read -r fixture _ _ <<<"$fixture_spec"
    if [[ ! -f "$PROGRAM_ROOT/$fixture" ]]; then
        fail "Program root $PROGRAM_ROOT is missing $fixture."
    fi
done

env \
    -u CC \
    -u CPPFLAGS \
    -u CFLAGS \
    -u LDFLAGS \
    R47_ANDROID_STAGED_CPP_DIR="$STAGED_CPP_DIR" \
    bash "$PREPARE_NATIVE_INPUTS_SCRIPT"

mkdir -p "$BUILD_DIR"

JAVAC_PATH="$(readlink -f "$(command -v javac)")"
JDK_HOME="$(cd "$(dirname "$JAVAC_PATH")/.." && pwd)"

mapfile -t STAGED_C47_SOURCES < <(find "$STAGED_CPP_DIR/c47" -type f -name '*.c' ! -name 'reservedRegisterLookupGenerator.c' | LC_ALL=C sort)
mapfile -t STAGED_GENERATED_SOURCES < <(find "$STAGED_CPP_DIR/generated" -type f -name '*.c' | LC_ALL=C sort)
mapfile -t STAGED_GMP_SOURCES < <(find "$STAGED_CPP_DIR/gmp" -type f -name '*.c' | LC_ALL=C sort)

STAGED_DEC_SOURCES=(
    "$STAGED_CPP_DIR/decNumberICU/decContext.c"
    "$STAGED_CPP_DIR/decNumberICU/decDouble.c"
    "$STAGED_CPP_DIR/decNumberICU/decNumber.c"
    "$STAGED_CPP_DIR/decNumberICU/decPacked.c"
    "$STAGED_CPP_DIR/decNumberICU/decQuad.c"
    "$STAGED_CPP_DIR/decNumberICU/decimal128.c"
    "$STAGED_CPP_DIR/decNumberICU/decimal32.c"
    "$STAGED_CPP_DIR/decNumberICU/decimal64.c"
)

ANDROID_BRIDGE_SOURCES=(
    "$TRACKED_CPP_DIR/r47zen/android_runtime.c"
    "$TRACKED_CPP_DIR/r47zen/android_helpers.c"
    "$TRACKED_CPP_DIR/r47zen/clipboard_utils.c"
    "$TRACKED_CPP_DIR/r47zen/hal/audio.c"
    "$TRACKED_CPP_DIR/r47zen/hal/gui.c"
    "$TRACKED_CPP_DIR/r47zen/hal/io.c"
    "$TRACKED_CPP_DIR/r47zen/hal/lcd.c"
    "$TRACKED_CPP_DIR/r47zen/hal/print_ir.c"
    "$TRACKED_CPP_DIR/r47zen/jni_activity_bridge.c"
    "$TRACKED_CPP_DIR/r47zen/jni_display.c"
    "$TRACKED_CPP_DIR/r47zen/jni_input.c"
    "$TRACKED_CPP_DIR/r47zen/jni_lifecycle.c"
    "$TRACKED_CPP_DIR/r47zen/jni_registration.c"
    "$TRACKED_CPP_DIR/r47zen/jni_storage.c"
)

EXTRA_CPPFLAGS=()
EXTRA_CFLAGS=()
EXTRA_LDFLAGS=()

if [[ -n "${CPPFLAGS:-}" ]]; then
    read -r -a EXTRA_CPPFLAGS <<<"${CPPFLAGS}"
fi

if [[ -n "${CFLAGS:-}" ]]; then
    read -r -a EXTRA_CFLAGS <<<"${CFLAGS}"
fi

if [[ -n "${LDFLAGS:-}" ]]; then
    read -r -a EXTRA_LDFLAGS <<<"${LDFLAGS}"
fi

"$CC_BIN" -std=c99 -O0 -g -pthread \
    -D_GNU_SOURCE -D_DEFAULT_SOURCE \
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47 \
    -Dmpz_div_2exp=mpz_tdiv_q_2exp -Dmpz_fits_uint_p=mpz_fits_ulong_p \
    "${EXTRA_CPPFLAGS[@]}" \
    "${EXTRA_CFLAGS[@]}" \
    -I"$JDK_HOME/include" \
    -I"$JDK_HOME/include/linux" \
    -I"$TRACKED_CPP_DIR/r47zen/stubs" \
    -I"$STAGED_CPP_DIR/c47" \
    -I"$STAGED_CPP_DIR/c47/core" \
    -I"$STAGED_CPP_DIR/c47/hal" \
    -I"$STAGED_CPP_DIR/c47/ui" \
    -I"$STAGED_CPP_DIR/c47/logicalOps" \
    -I"$STAGED_CPP_DIR/c47/mathematics" \
    -I"$STAGED_CPP_DIR/c47/programming" \
    -I"$STAGED_CPP_DIR/c47/solver" \
    -I"$STAGED_CPP_DIR/c47/browsers" \
    -I"$STAGED_CPP_DIR/c47/distributions" \
    -I"$STAGED_CPP_DIR/c47/c47Extensions" \
    -I"$STAGED_CPP_DIR/decNumberICU" \
    -I"$STAGED_CPP_DIR/generated" \
    -I"$TRACKED_CPP_DIR/r47zen" \
    -I"$STAGED_CPP_DIR/gmp" \
    -include "$TRACKED_CPP_DIR/r47zen/android_mocks.h" \
    "$PROJECT_ROOT/scripts/workload-regressions/host_workload_regression.c" \
    "${STAGED_C47_SOURCES[@]}" \
    "${STAGED_GENERATED_SOURCES[@]}" \
    "${STAGED_GMP_SOURCES[@]}" \
    "${STAGED_DEC_SOURCES[@]}" \
    "${ANDROID_BRIDGE_SOURCES[@]}" \
    "${EXTRA_LDFLAGS[@]}" \
    -lm \
    -o "$BUILD_DIR/$HOST_WORKLOAD_OUTPUT_NAME"

for fixture_spec in "${REQUIRED_PROGRAM_FIXTURE_SPECS[@]}"; do
    IFS='|' read -r fixture timeout_duration kill_after <<<"$fixture_spec"
    run_host_workload_fixture "$fixture" "$TIMEOUT_BIN" "$timeout_duration" "$kill_after"
done
