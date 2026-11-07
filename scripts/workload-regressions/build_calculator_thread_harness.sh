#!/bin/bash
# Build and run the calculator thread harness under AddressSanitizer and
# UndefinedBehaviorSanitizer. It runs upstream's main loop on a thread of its own,
# as the app does, and proves what that model promises: a key during a computation
# never re-enters the core, EXIT and R/S stop one, a save waits for rest, and OFF
# saves and keeps the calculator alive.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/calculator-thread-harness"
CC_BIN="${CC:-cc}"
mkdir -p "$BUILD_DIR"

JDK_HOME="$(resolve_jdk_home)"

# The core files upstream compiles, from its own list (r47_core_c47_sources).
c47_list="$(r47_core_c47_sources "$STAGED_CPP_DIR/c47")"
mapfile -t STAGED_C47_SOURCES <<<"$c47_list"
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

mapfile -t ANDROID_BRIDGE_SOURCES < <(r47_glue_sources "$TRACKED_CPP_DIR")
[ "${#ANDROID_BRIDGE_SOURCES[@]}" -gt 0 ] || exit 1

COMPILE_FLAGS=(
    -std=gnu17 -O1 -g -pthread -fsigned-char -fno-strict-aliasing
    "-fsanitize=address,undefined" -fno-sanitize=alignment -fno-sanitize-recover=undefined
    -fno-omit-frame-pointer -w
    -D_GNU_SOURCE -D_DEFAULT_SOURCE
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47
    -I"$JDK_HOME/include"
    -I"$JDK_HOME/include/linux"
    -I"$TRACKED_CPP_DIR/r47zen/stubs"
    -I"$STAGED_CPP_DIR/c47"
    -I"$STAGED_CPP_DIR/c47/core"
    -I"$STAGED_CPP_DIR/c47/hal"
    -I"$STAGED_CPP_DIR/c47/ui"
    -I"$STAGED_CPP_DIR/c47/logicalOps"
    -I"$STAGED_CPP_DIR/c47/mathematics"
    -I"$STAGED_CPP_DIR/c47/programming"
    -I"$STAGED_CPP_DIR/c47/solver"
    -I"$STAGED_CPP_DIR/c47/browsers"
    -I"$STAGED_CPP_DIR/c47/distributions"
    -I"$STAGED_CPP_DIR/c47/c47Extensions"
    -I"$STAGED_CPP_DIR/decNumberICU"
    -I"$STAGED_CPP_DIR/generated"
    -I"$TRACKED_CPP_DIR/r47zen"
    -I"$STAGED_CPP_DIR/gmp"
    -include "$TRACKED_CPP_DIR/r47zen/android_mocks.h"
)

OBJECT_DIR="$BUILD_DIR/objects"
rm -rf "$OBJECT_DIR"
mkdir -p "$OBJECT_DIR"
printf '"%s"\n' "${COMPILE_FLAGS[@]}" >"$BUILD_DIR/compile.rsp"

compile_unit() {
    "$CC_BIN" "@$BUILD_DIR/compile.rsp" -c "$1" -o "$OBJECT_DIR/$(printf '%s' "$1" | tr '/' '_').o"
}
export CC_BIN BUILD_DIR OBJECT_DIR
export -f compile_unit
printf '%s\0' \
    "$SCRIPT_DIR/calculator_thread_harness.c" \
    "${STAGED_C47_SOURCES[@]}" \
    "${STAGED_GENERATED_SOURCES[@]}" \
    "${STAGED_GMP_SOURCES[@]}" \
    "${STAGED_DEC_SOURCES[@]}" \
    "${ANDROID_BRIDGE_SOURCES[@]}" |
    xargs -0 -n 1 -P "$(nproc)" bash -c 'compile_unit "$0"'

# A definition in hal/ that nothing refers to reads as a live seam with upstream.
# Prints each one. The arguments are the HAL's objects, then --, then every object.
# A __wrap_ definition is referred to by the link, which renames the wrapped calls.
unreferenced_hal_definitions() {
    local hal_objects=()
    while [ "$1" != "--" ]; do
        hal_objects+=("$1")
        shift
    done
    shift
    comm -23 \
        <(nm -g --defined-only "${hal_objects[@]}" | awk 'NF == 3 { print $3 }' | grep -v '^__odr_asan\|^__wrap_' | LC_ALL=C sort -u) \
        <(nm -u "$@" | awk '$1 == "U" { print $2 }' | LC_ALL=C sort -u)
}

SEED_DIR="$BUILD_DIR/seed"
mkdir -p "$SEED_DIR"
echo 'void aDefinitionNothingCalls(void) {}' >"$SEED_DIR/seed.c"
"$CC_BIN" -c "$SEED_DIR/seed.c" -o "$SEED_DIR/seed.o"
[ "$(unreferenced_hal_definitions "$SEED_DIR/seed.o" -- "$SEED_DIR/seed.o")" = "aDefinitionNothingCalls" ] || {
    echo "ERROR: the unreferenced HAL definition check did not see its seed." >&2
    exit 1
}

# Each of these has its caller in a build this harness does not link: the program-load
# test bridge for the display buffer and its lock, and the workload harness for the counter
# and for handing the core to its worker thread.
USED_OUTSIDE_THIS_HARNESS='packedDisplayBuffer
packedDisplayMutex
r47_keys_claim_calculator_thread
r47_reset_host_lcd_refresh_count'
unreferenced="$(unreferenced_hal_definitions "$OBJECT_DIR"/*_r47zen_hal_*.o -- "$OBJECT_DIR"/*.o)"
unexpected="$(comm -23 <(printf '%s\n' "$unreferenced") <(printf '%s\n' "$USED_OUTSIDE_THIS_HARNESS" | LC_ALL=C sort -u))"
[ -z "$unexpected" ] || {
    echo "ERROR: HAL definitions nothing refers to:" >&2
    echo "$unexpected" >&2
    exit 1
}

# Only the calculator thread runs core code, and a JNI entry runs on its caller's thread,
# so no route from one may name a core symbol. Each glue definition gets its own section,
# so a relocation section names what that definition refers to. The check sees its seed
# first: an entry reaching a core global through a static helper, among the real glue.
core_reach() {
    python3 -I "$SCRIPT_DIR/../r47_contracts/jni_core_reach.py" "$@"
}
REACH_DIR="$BUILD_DIR/reach"
rm -rf "$REACH_DIR"
mkdir -p "$REACH_DIR/glue" "$REACH_DIR/core" "$REACH_DIR/seeded-glue"
REACH_FLAGS=(-fno-sanitize=all -O0 -g0 -ffunction-sections -fdata-sections)
for source in "${ANDROID_BRIDGE_SOURCES[@]}"; do
    "$CC_BIN" "@$BUILD_DIR/compile.rsp" "${REACH_FLAGS[@]}" -c "$source" \
        -o "$REACH_DIR/glue/$(printf '%s' "$source" | tr '/' '_').o"
done
# The core is what the staged tree compiled; an object name is its source path.
find "$OBJECT_DIR" -name '*_.staged-native_cpp_*.o' -exec ln -s {} "$REACH_DIR/core/" \;
cp "$REACH_DIR"/glue/*.o "$REACH_DIR/seeded-glue/"
cat >"$REACH_DIR/seed.c" <<'C'
extern int programRunStop;
static int helper(void) { return programRunStop; }
int Java_seed_entry(void) { return helper(); }
C
"$CC_BIN" "${REACH_FLAGS[@]}" -c "$REACH_DIR/seed.c" -o "$REACH_DIR/seeded-glue/seed.o"
seed_routes="$(core_reach "$REACH_DIR/seeded-glue" "$REACH_DIR/core" || true)"
[ "$seed_routes" = "Java_seed_entry -> helper -> programRunStop" ] || {
    echo "ERROR: the JNI reach check did not see its seed: $seed_routes" >&2
    exit 1
}
if ! routes="$(core_reach "$REACH_DIR/glue" "$REACH_DIR/core")"; then
    echo "ERROR: a JNI entry reaches core code off the calculator thread:" >&2
    echo "$routes" >&2
    exit 1
fi

"$CC_BIN" -pthread -fsanitize=address,undefined \
    -Wl,--wrap=exit \
    -Wl,--wrap=remove \
    -Wl,--wrap=r47_upcall_play_tone \
    -Wl,--wrap=r47_upcall_tones_clear \
    -Wl,--wrap=r47_upcall_quit \
    -Wl,--wrap=r47_upcall_paste_result \
    -Wl,--wrap=r47_upcall_warning \
    -Wl,--wrap=fsync \
    -Wl,--wrap=fwrite \
    -Wl,--wrap=pthread_cond_timedwait \
    "$OBJECT_DIR"/*.o \
    -lm \
    -o "$BUILD_DIR/calculator-thread-harness"

# The fixtures reach the harness through a directory whose name holds a quote and a
# space, so a path the harness put into a shell command would break the run.
FIXTURES_DIR="$BUILD_DIR/fixtures with a ' quote"
rm -rf "$FIXTURES_DIR"
mkdir -p "$FIXTURES_DIR"
cp "$SCRIPT_DIR"/fixtures/*.cfg.gz "$FIXTURES_DIR/"

echo "Built: $BUILD_DIR/calculator-thread-harness"
echo "Running under ASan/UBSan..."
rm -rf "$BUILD_DIR/run"
ASAN_OPTIONS="abort_on_error=1:halt_on_error=1:detect_leaks=0" \
    UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=1" \
    R47_STATE_FIXTURES="$FIXTURES_DIR" \
    "$BUILD_DIR/calculator-thread-harness" "$BUILD_DIR/run" "$PROJECT_ROOT/res/PROGRAMS"
