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
REQUIRED_PROGRAM_FIXTURES=(
    "BinetV3.p47"
    "GudrmPL.p47"
    "NQueens.p47"
    "SPIRALk.p47"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
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

for fixture in "${REQUIRED_PROGRAM_FIXTURES[@]}"; do
    if [[ ! -f "$PROGRAM_ROOT/$fixture" ]]; then
        fail "Program root $PROGRAM_ROOT is missing $fixture."
    fi
done

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
    "$TRACKED_CPP_DIR/r47_android/android_runtime.c"
    "$TRACKED_CPP_DIR/r47_android/android_helpers.c"
    "$TRACKED_CPP_DIR/r47_android/clipboard_utils.c"
    "$TRACKED_CPP_DIR/r47_android/hal/audio.c"
    "$TRACKED_CPP_DIR/r47_android/hal/gui.c"
    "$TRACKED_CPP_DIR/r47_android/hal/io.c"
    "$TRACKED_CPP_DIR/r47_android/hal/lcd.c"
    "$TRACKED_CPP_DIR/r47_android/hal/print_ir.c"
    "$TRACKED_CPP_DIR/r47_android/jni_activity_bridge.c"
    "$TRACKED_CPP_DIR/r47_android/jni_display.c"
    "$TRACKED_CPP_DIR/r47_android/jni_input.c"
    "$TRACKED_CPP_DIR/r47_android/jni_lifecycle.c"
    "$TRACKED_CPP_DIR/r47_android/jni_registration.c"
    "$TRACKED_CPP_DIR/r47_android/jni_storage.c"
)

EXTRA_CPPFLAGS=()
EXTRA_CFLAGS=()
EXTRA_LDFLAGS=()

if [[ -n "${CPPFLAGS:-}" ]]; then
    read -r -a EXTRA_CPPFLAGS <<< "${CPPFLAGS}"
fi

if [[ -n "${CFLAGS:-}" ]]; then
    read -r -a EXTRA_CFLAGS <<< "${CFLAGS}"
fi

if [[ -n "${LDFLAGS:-}" ]]; then
    read -r -a EXTRA_LDFLAGS <<< "${LDFLAGS}"
fi

"$CC_BIN" -std=c99 -O0 -g -pthread \
    -D_GNU_SOURCE -D_DEFAULT_SOURCE \
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47 \
    -Dmpz_div_2exp=mpz_tdiv_q_2exp -Dmpz_fits_uint_p=mpz_fits_ulong_p \
    "${EXTRA_CPPFLAGS[@]}" \
    "${EXTRA_CFLAGS[@]}" \
    -I"$JDK_HOME/include" \
    -I"$JDK_HOME/include/linux" \
    -I"$TRACKED_CPP_DIR/r47_android/stubs" \
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
    -I"$TRACKED_CPP_DIR/r47_android" \
    -I"$STAGED_CPP_DIR/gmp" \
    -include "$TRACKED_CPP_DIR/r47_android/android_mocks.h" \
    "$PROJECT_ROOT/scripts/workload-regressions/host_workload_regression.c" \
    "${STAGED_C47_SOURCES[@]}" \
    "${STAGED_GENERATED_SOURCES[@]}" \
    "${STAGED_GMP_SOURCES[@]}" \
    "${STAGED_DEC_SOURCES[@]}" \
    "${ANDROID_BRIDGE_SOURCES[@]}" \
    "${EXTRA_LDFLAGS[@]}" \
    -lm \
    -o "$BUILD_DIR/$HOST_WORKLOAD_OUTPUT_NAME"

"$BUILD_DIR/$HOST_WORKLOAD_OUTPUT_NAME" --program-root "$PROGRAM_ROOT"
