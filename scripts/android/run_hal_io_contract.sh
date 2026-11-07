#!/bin/bash

# Build and run the host contract tests for hal/io.c on Linux. Each links hal/io.c
# alone; no emulator or device is required.
#
# scripts/android/hal_io_contract_test.c, with the host build flag that compiles
# out the SAF branch: a write the file system did not take is reported once per
# file at ioFileClose, and nothing else is.
#
# scripts/android/hal_io_saf_contract_test.c, with that branch compiled in: each
# file path asks for the name and folder it should, a save through a pipe arrives
# whole with nothing reported, and a finished SNAP goes to the picker once and
# leaves the next one a name.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
STAGED_CPP_DIR="${R47_ANDROID_STAGED_CPP_DIR:-$PROJECT_ROOT/android/.staged-native/cpp}"
BUILD_DIR="${R47_HAL_IO_CONTRACT_BUILD_DIR:-$PROJECT_ROOT/android/build/hal-io-contract}"
CC_BIN="${CC:-cc}"

if [ ! -f "$STAGED_CPP_DIR/c47/defines.h" ]; then
    echo "ERROR: Missing staged native inputs at $STAGED_CPP_DIR." >&2
    echo "Run ./scripts/android/build_android.sh or scripts/android/stage_native_sources.sh first." >&2
    exit 1
fi

jdk_home="$(resolve_jdk_home)"

mkdir -p "$BUILD_DIR"

build_contract() {
    local output="$1"
    local source="$2"
    shift 2
    "$CC_BIN" -std=gnu17 -O0 -g -pthread \
        -DANDROID_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47 "$@" \
        -I"$jdk_home/include" \
        -I"$jdk_home/include/linux" \
        -I"$TRACKED_CPP_DIR/stubs" \
        -I"$STAGED_CPP_DIR/c47" \
        -I"$STAGED_CPP_DIR/c47/core" \
        -I"$STAGED_CPP_DIR/c47/hal" \
        -I"$STAGED_CPP_DIR/c47/ui" \
        -I"$STAGED_CPP_DIR/decNumberICU" \
        -I"$STAGED_CPP_DIR/generated" \
        -I"$TRACKED_CPP_DIR" \
        -I"$STAGED_CPP_DIR/gmp" \
        -include "$TRACKED_CPP_DIR/android_mocks.h" \
        "$source" \
        "$TRACKED_CPP_DIR/hal/io.c" \
        -Wl,--wrap=remove \
        -lm \
        -o "$output"
}

build_contract "$BUILD_DIR/hal-io-contract" "$SCRIPT_DIR/hal_io_contract_test.c" -DHOST_TOOL_BUILD
build_contract "$BUILD_DIR/hal-io-saf-contract" "$SCRIPT_DIR/hal_io_saf_contract_test.c"

echo "--- Running host hal/io.c write-failure contract test ---"
"$BUILD_DIR/hal-io-contract"
echo "--- Running host hal/io.c SAF contract test ---"
"$BUILD_DIR/hal-io-saf-contract"
