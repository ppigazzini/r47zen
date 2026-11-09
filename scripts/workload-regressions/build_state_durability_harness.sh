#!/bin/bash
# Build and run the state-file durability harness under AddressSanitizer and
# UndefinedBehaviorSanitizer: the staged core and the glue, with the app's link wraps
# of exit and remove, and of the warning up-call so a case can count what the user
# is told. Each case restores from its own work directory in a fresh process; the
# released state files come from scripts/workload-regressions/fixtures/, which
# make_state_fixture.sh saves with a host build of each release.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/state-durability-harness"
CC_BIN="${CC:-cc}"
mkdir -p "$BUILD_DIR"

JDK_HOME="$(resolve_jdk_home)"

# The core files upstream compiles, from its own list (r47_core_c47_sources).
c47_list="$(r47_core_c47_sources "$STAGED_CPP_DIR/c47")"
mapfile -t STAGED_C47_SOURCES <<<"$c47_list"
mapfile -t STAGED_GENERATED_SOURCES < <(find "$STAGED_CPP_DIR/generated" -type f -name '*.c' | LC_ALL=C sort)
mapfile -t STAGED_GMP_SOURCES < <(find "$STAGED_CPP_DIR/gmp" -type f -name '*.c' | LC_ALL=C sort)
# decDouble.c and decQuad.c include decBasic.c and decCommon.c themselves.
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
mapfile -t ANDROID_BRIDGE_SOURCES < <(r47_glue_sources "$TRACKED_CPP_DIR")
[ "${#ANDROID_BRIDGE_SOURCES[@]}" -gt 0 ] || exit 1

# ASan halts on any memory error; UBSan reports and goes on, as in the clipboard
# harness, since the core trips undefined behavior this repo cannot fix in src/.
"$CC_BIN" -std=gnu17 -O1 -g -pthread \
    -fsanitize=address,undefined -fno-sanitize=alignment \
    -fno-omit-frame-pointer \
    -fsigned-char -fno-strict-aliasing \
    -D_GNU_SOURCE -D_DEFAULT_SOURCE \
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47 \
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
    "$SCRIPT_DIR/state_durability_harness.c" \
    "${STAGED_C47_SOURCES[@]}" \
    "${STAGED_GENERATED_SOURCES[@]}" \
    "${STAGED_GMP_SOURCES[@]}" \
    "${STAGED_DEC_SOURCES[@]}" \
    "${ANDROID_BRIDGE_SOURCES[@]}" \
    -Wl,--wrap=exit \
    -Wl,--wrap=remove \
    -Wl,--wrap=r47_show_warning \
    -lm \
    -o "$BUILD_DIR/state-durability-harness"

echo "Built: $BUILD_DIR/state-durability-harness"
echo "Running under ASan/UBSan..."
ASAN_OPTIONS="abort_on_error=1:halt_on_error=1:detect_leaks=0" \
    UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=0" \
    R47_STATE_FIXTURES="$SCRIPT_DIR/fixtures" \
    "$BUILD_DIR/state-durability-harness" "$PROJECT_ROOT/res/PROGRAMS"
