#!/bin/bash
# Build and run the JNI-bridge concurrency harness under ThreadSanitizer.
#
# ThreadSanitizer is the hard gate for repo-owned synchronization: a data race
# or lock-order inversion in the Android bridge (android/app/src/main/cpp/r47zen)
# aborts the run. The upstream-owned C47 core is driven only under the bridge's
# own locks, so it must not contribute races; any that survive come with a
# checked-in suppression file scoped to upstream paths, the same way the
# AddressSanitizer/UndefinedBehaviorSanitizer lane handles un-ownable upstream
# undefined behavior. The harness itself (bridge_tsan_harness.c) races the live
# input/refresh producer path against the UI read path so a missing acquire on a
# generation counter, a torn read across a trylock fast path, or a lock-order
# inversion between the two display mutexes fails the run.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/bridge-tsan-harness"
SUPPRESSIONS_FILE="$SCRIPT_DIR/bridge_tsan_suppressions.txt"
CC_BIN="${CC:-cc}"
mkdir -p "$BUILD_DIR"

if [ ! -f "$STAGED_CPP_DIR/c47/defines.h" ]; then
    echo "ERROR: Missing staged native inputs at $STAGED_CPP_DIR." >&2
    echo "Run ./scripts/android/build_android.sh or scripts/android/stage_native_sources.sh first." >&2
    exit 1
fi

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

# ThreadSanitizer instruments the whole program (the bridge plus the staged
# core) so cross-thread accesses are tracked. Built at -O1 -g with frame
# pointers for readable race reports; the bridge's own locks serialize the core,
# so the core's single-threaded UB (handled by the ASan/UBSan lane) is not
# exercised here.
"$CC_BIN" -std=gnu11 -O1 -g -pthread \
    -fsanitize=thread \
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
    "$SCRIPT_DIR/bridge_tsan_harness.c" \
    "${STAGED_C47_SOURCES[@]}" \
    "${STAGED_GENERATED_SOURCES[@]}" \
    "${STAGED_GMP_SOURCES[@]}" \
    "${STAGED_DEC_SOURCES[@]}" \
    "${ANDROID_BRIDGE_SOURCES[@]}" \
    -lm \
    -o "$BUILD_DIR/bridge-tsan-harness"

echo "Built: $BUILD_DIR/bridge-tsan-harness"
echo "Running under ThreadSanitizer..."

# halt_on_error=1 makes the first repo-owned race abort the run (the hard gate).
# A suppression file scoped to upstream-owned paths keeps un-ownable core races
# (if any surface) from going permanently red, matching the UBSan precedent. The
# file is optional: absent it, every race is a hard failure.
TSAN_OPTS="halt_on_error=1:exitcode=66:second_deadlock_stack=1"
if [ -f "$SUPPRESSIONS_FILE" ]; then
    TSAN_OPTS="$TSAN_OPTS:suppressions=$SUPPRESSIONS_FILE"
fi

# ThreadSanitizer maps a fixed shadow region and aborts with "unexpected memory
# mapping" when the loader places the binary at a high-entropy ASLR address
# (common on recent kernels). Disable per-process randomization with setarch -R
# when it is available and effective; otherwise run directly and let the rare
# mapping abort surface rather than masking it.
RUNNER=()
if command -v setarch >/dev/null 2>&1 && setarch -R true >/dev/null 2>&1; then
    RUNNER=(setarch -R)
fi

TSAN_OPTIONS="$TSAN_OPTS" "${RUNNER[@]}" "$BUILD_DIR/bridge-tsan-harness"
echo "ThreadSanitizer harness passed: no repo-owned data races."
