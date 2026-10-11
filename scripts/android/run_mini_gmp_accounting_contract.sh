#!/bin/bash

# Contract: the mini-gmp the Android build ships hands the core's GMP accounting
# hooks the real block size. Upstream mini-gmp passes size 0 to the free and
# realloc hooks, which leaves the core's gmpMemInBytes counter growing; on the
# Android build, which defines PC_BUILD, upstream items.c then reports
# "gmpMemInBytes should be 0" (sprintf, fprintf, fflush) after every operation.
#
# The contract compiles mini_gmp_accounting_contract_test.c against
# android/compat/mini-gmp-fallback/mini-gmp.c, the file
# stage_native_sources.sh stages, with counting hooks, and runs it under
# AddressSanitizer and UBSan. It first proves the test fails on a copy whose
# free hands the hook size 0, the upstream form.
#
# Pure host: no SDK, no staged native tree.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MINI_GMP_DIR="$PROJECT_ROOT/android/compat/mini-gmp-fallback"
BUILD_DIR="${R47_MINI_GMP_CONTRACT_BUILD_DIR:-$PROJECT_ROOT/android/build/mini-gmp-accounting-contract}"
CC_BIN="${CC:-cc}"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[ -f "$MINI_GMP_DIR/mini-gmp.c" ] ||
    fail "missing required file: ${MINI_GMP_DIR#"$PROJECT_ROOT/"}/mini-gmp.c"

mkdir -p "$BUILD_DIR"

SANITIZE=("-fsanitize=address,undefined" -fno-sanitize-recover=all)

# build_contract_test MINI_GMP_C OUTPUT. The warning gate covers the test, not
# the vendored mini-gmp.
build_contract_test() {
    "$CC_BIN" -std=gnu17 -O1 -g "${SANITIZE[@]}" -I"$MINI_GMP_DIR" \
        -c "$1" -o "$2.mini-gmp.o"
    "$CC_BIN" -std=gnu17 -O1 -g -Wall -Wextra -Werror "${SANITIZE[@]}" -I"$MINI_GMP_DIR" \
        "$SCRIPT_DIR/mini_gmp_accounting_contract_test.c" "$2.mini-gmp.o" \
        -o "$2"
}

# The upstream form: the free hook is handed size 0.
size_zero_copy="$BUILD_DIR/mini-gmp-size-zero-free.c"
sed 's/(\*gmp_free_func) (base, total);/(*gmp_free_func) (base, 0);/' \
    "$MINI_GMP_DIR/mini-gmp.c" >"$size_zero_copy"
cmp -s "$MINI_GMP_DIR/mini-gmp.c" "$size_zero_copy" &&
    fail "could not seed the size-0 free into a copy of mini-gmp.c; the free hook call changed shape."
build_contract_test "$size_zero_copy" "$BUILD_DIR/size-zero-free"
if "$BUILD_DIR/size-zero-free" >"$BUILD_DIR/size-zero-free.log" 2>&1; then
    fail "the accounting test passed on a mini-gmp whose free hands the hook size 0; it cannot see the defect it exists for."
fi

echo "--- Running the mini-gmp accounting contract test ---"
build_contract_test "$MINI_GMP_DIR/mini-gmp.c" "$BUILD_DIR/mini-gmp-accounting-contract"
"$BUILD_DIR/mini-gmp-accounting-contract"
