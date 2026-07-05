#!/bin/bash
# Build and run the graph-crash reproduction harness under AddressSanitizer
# and UndefinedBehaviorSanitizer.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/graph-crash-harness"
CC_BIN="${CC:-cc}"
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

# AddressSanitizer is the hard gate: any memory error aborts the run (ASan halts
# by default and ASAN_OPTIONS below makes it explicit), so a use-after-free,
# overflow, or bad free fails the harness. UndefinedBehaviorSanitizer runs
# alongside in recoverable report mode (no -fno-sanitize-recover; halt_on_error=0
# below): the upstream-owned C47 core trips portable-but-UB constructs that this
# repo cannot fix in src/ (negative left shifts in screen.c, byte-offset casts to
# aligned structs in manage.c), so UBSan surfaces every finding for triage
# without turning the lane red on un-ownable upstream UB. The alignment check is
# dropped outright because the core's single RAM blob casts unaligned offsets to
# programList_t on every program scan, which is pure host-layout noise.
"$CC_BIN" -std=gnu11 -O1 -g -pthread \
    -fsanitize=address,undefined -fno-sanitize=alignment \
    -fno-omit-frame-pointer \
    -D_GNU_SOURCE -D_DEFAULT_SOURCE \
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47 \
    -Dmpz_div_2exp=mpz_tdiv_q_2exp -Dmpz_fits_uint_p=mpz_fits_ulong_p \
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
    "$SCRIPT_DIR/graph_crash_harness.c" \
    "${STAGED_C47_SOURCES[@]}" \
    "${STAGED_GENERATED_SOURCES[@]}" \
    "${STAGED_GMP_SOURCES[@]}" \
    "${STAGED_DEC_SOURCES[@]}" \
    "${ANDROID_BRIDGE_SOURCES[@]}" \
    -lm \
    -o "$BUILD_DIR/graph-crash-harness"

echo "Built: $BUILD_DIR/graph-crash-harness"
echo "Running under ASan/UBSan..."
ASAN_OPTIONS="abort_on_error=1:halt_on_error=1" \
    UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=0" \
    "$BUILD_DIR/graph-crash-harness"
