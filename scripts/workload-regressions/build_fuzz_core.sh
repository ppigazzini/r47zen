#!/bin/bash
# Build fuzz_core.c with the staged core and the glue under libFuzzer, AddressSanitizer,
# and UndefinedBehaviorSanitizer, then fuzz each reader of a file a user hands the
# calculator, seeded from upstream's own files: res/PROGRAMS for programs, res/STATE for
# state files, res/DATA for data files, and for the state file the app restores, one this
# core saves and the released cores' fixtures. A crash fails the run and is kept under
# android/build/fuzz-core/artifacts.
#
# R47_FUZZ_SECONDS sets the time per reader (default 60). R47_FUZZ_TARGETS limits the
# readers (default "program backup state data").
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/fuzz-core"
CC_BIN="${CC:-clang}"
SECONDS_PER_TARGET="${R47_FUZZ_SECONDS:-60}"
read -r -a TARGETS <<<"${R47_FUZZ_TARGETS:-program backup state data}"

JDK_HOME="$(resolve_jdk_home)"

mapfile -t SOURCES < <(
    find "$STAGED_CPP_DIR/c47" -type f -name '*.c' ! -name 'reservedRegisterLookupGenerator.c'
    find "$STAGED_CPP_DIR/generated" "$STAGED_CPP_DIR/gmp" -type f -name '*.c'
    for name in decContext decDouble decNumber decPacked decQuad decimal128 decimal32 decimal64; do
        echo "$STAGED_CPP_DIR/decNumberICU/$name.c"
    done
    r47_glue_sources "$TRACKED_CPP_DIR"
)

OBJECT_DIR="$BUILD_DIR/objects"
rm -rf "$BUILD_DIR"
mkdir -p "$OBJECT_DIR" "$BUILD_DIR/artifacts" "$BUILD_DIR/tmp"
COMPILE_FLAGS=(
    -std=gnu17 -O1 -g -pthread -w -fsigned-char -fno-strict-aliasing
    "-fsanitize=fuzzer-no-link,address,undefined" -fno-sanitize=alignment
    -fno-sanitize-recover=undefined -fno-omit-frame-pointer
    -D_GNU_SOURCE -D_DEFAULT_SOURCE
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47
    -I"$JDK_HOME/include" -I"$JDK_HOME/include/linux"
    -I"$TRACKED_CPP_DIR/r47zen/stubs"
    -I"$STAGED_CPP_DIR/c47" -I"$STAGED_CPP_DIR/c47/core" -I"$STAGED_CPP_DIR/c47/hal"
    -I"$STAGED_CPP_DIR/c47/ui" -I"$STAGED_CPP_DIR/c47/logicalOps"
    -I"$STAGED_CPP_DIR/c47/mathematics" -I"$STAGED_CPP_DIR/c47/programming"
    -I"$STAGED_CPP_DIR/c47/solver" -I"$STAGED_CPP_DIR/c47/browsers"
    -I"$STAGED_CPP_DIR/c47/distributions" -I"$STAGED_CPP_DIR/c47/c47Extensions"
    -I"$STAGED_CPP_DIR/decNumberICU" -I"$STAGED_CPP_DIR/generated"
    -I"$TRACKED_CPP_DIR/r47zen" -I"$STAGED_CPP_DIR/gmp"
    -include "$TRACKED_CPP_DIR/r47zen/android_mocks.h"
)
printf '"%s"\n' "${COMPILE_FLAGS[@]}" >"$BUILD_DIR/compile.rsp"

compile_unit() {
    "$CC_BIN" "@$BUILD_DIR/compile.rsp" -c "$1" -o "$OBJECT_DIR/$(printf '%s' "$1" | tr '/' '_').o"
}
export CC_BIN BUILD_DIR OBJECT_DIR
export -f compile_unit
printf '%s\0' "$SCRIPT_DIR/fuzz_core.c" "${SOURCES[@]}" |
    xargs -0 -n 1 -P "$(nproc)" bash -c 'compile_unit "$0"'

# exit is not wrapped as the app wraps it: the wrap would reach libFuzzer's own exit at
# the end of a run. libFuzzer reports a target that exits as a finding by itself.
"$CC_BIN" -pthread "-fsanitize=fuzzer,address,undefined" \
    -Wl,--wrap=remove \
    "$OBJECT_DIR"/*.o -lm -o "$BUILD_DIR/fuzz-core"

export ASAN_OPTIONS="detect_leaks=0:allocator_may_return_null=1:abort_on_error=1"
export UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=1"
export FUZZ_TMP="$BUILD_DIR/tmp"

corpus="$BUILD_DIR/corpus"
mkdir -p "$corpus/program" "$corpus/backup" "$corpus/state" "$corpus/data"
cp "$PROJECT_ROOT"/res/PROGRAMS/*.p47 "$corpus/program/"
cp "$PROJECT_ROOT"/res/STATE/*.s47 "$corpus/state/"
cp "$PROJECT_ROOT"/res/DATA/*.d47 "$corpus/data/"
for fixture in "$SCRIPT_DIR"/fixtures/*.cfg.gz; do
    gzip -dc "$fixture" >"$corpus/backup/$(basename "$fixture" .gz)"
done
# This core's own files, with a program and registers in them: the restore's state
# file, and a SAVEST file, whose sections the two res/STATE files do not have.
for seed in backup state; do
    seed_file="$corpus/$seed/this-core.$seed"
    env "FUZZ_SEED_${seed^^}=$seed_file" FUZZ_SEED_PROGRAM="$PROJECT_ROOT/res/PROGRAMS/FactIt.p47" \
        FUZZ_TARGET="$seed" "$BUILD_DIR/fuzz-core" -runs=0 >/dev/null 2>&1 || true
    [ -s "$seed_file" ] || {
        echo "ERROR: the fuzzer saved no $seed file to seed with." >&2
        exit 1
    }
done
# The section and key names the seeds use, as a dictionary for every reader.
grep -hoE '^[A-Z][A-Z0-9_]{3,}$' "$corpus"/state/* "$corpus"/data/* | LC_ALL=C sort -u |
    sed 's/.*/"&"/' >"$BUILD_DIR/sections.dict"

failed=()
for target in "${TARGETS[@]}"; do
    echo "--- fuzzing $target for ${SECONDS_PER_TARGET}s ---"
    if ! FUZZ_TARGET="$target" "$BUILD_DIR/fuzz-core" \
        -artifact_prefix="$BUILD_DIR/artifacts/$target-" \
        -max_total_time="$SECONDS_PER_TARGET" -timeout=20 -rss_limit_mb=3000 \
        -max_len=2000000 -len_control=0 -print_final_stats=1 -close_fd_mask=3 \
        -dict="$BUILD_DIR/sections.dict" \
        "$corpus/$target" >"$BUILD_DIR/$target.log" 2>&1; then
        failed+=("$target")
        # The kind of fault and its stack, and nothing else: libFuzzer prints a short
        # input itself, in hex and Base64, inside an out-of-memory report, and the
        # input would be a working reproducer in a public log.
        grep -E 'ERROR|SUMMARY|runtime error|^ +#[0-9]+ ' "$BUILD_DIR/$target.log" >&2 || true
    fi
    grep -E '^stat::number_of_executed_units|^stat::peak_rss_mb' "$BUILD_DIR/$target.log" || true
done

if [ "${#failed[@]}" -ne 0 ]; then
    echo "FAIL: a crash in ${failed[*]}; inputs are in $BUILD_DIR/artifacts" >&2
    exit 1
fi
echo "OK: no crash in ${TARGETS[*]} after ${SECONDS_PER_TARGET}s each."
