#!/bin/bash

# Build and run the host keypad-snapshot generation contract test on Linux.
# No emulator or device is required: it links hal/lcd.c on the host and asserts
# that a screen refresh (LCD_write_line) bumps keypadSnapshotGeneration, which
# the Android display loop relies on to re-read dynamic softkeys such as the EQN
# editor. See scripts/android/keypad_generation_contract_test.c.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
STAGED_CPP_DIR="${R47_ANDROID_STAGED_CPP_DIR:-$PROJECT_ROOT/android/.staged-native/cpp}"
BUILD_DIR="${R47_KEYPAD_CONTRACT_BUILD_DIR:-$PROJECT_ROOT/android/build/keypad-generation-contract}"
CC_BIN="${CC:-cc}"

if [ ! -f "$STAGED_CPP_DIR/c47/defines.h" ]; then
    echo "ERROR: Missing staged native inputs at $STAGED_CPP_DIR." >&2
    echo "Run ./scripts/android/build_android.sh or scripts/android/stage_native_sources.sh first." >&2
    exit 1
fi

javac_path="$(readlink -f "$(command -v javac)")"
jdk_home="$(cd "$(dirname "$javac_path")/.." && pwd)"

mkdir -p "$BUILD_DIR"
output="$BUILD_DIR/keypad-generation-contract"

"$CC_BIN" -std=c11 -O0 -g -pthread \
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47 \
    -Dmpz_div_2exp=mpz_tdiv_q_2exp -Dmpz_fits_uint_p=mpz_fits_ulong_p \
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
    "$SCRIPT_DIR/keypad_generation_contract_test.c" \
    "$TRACKED_CPP_DIR/hal/lcd.c" \
    -lm \
    -o "$output"

echo "--- Running host keypad-snapshot generation contract test ---"
"$output"
