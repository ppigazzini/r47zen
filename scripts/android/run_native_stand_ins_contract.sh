#!/bin/bash
# Contract: the glue's stand-ins for headers the NDK lacks at the app's minSdk, or
# that upstream takes from GMP, give what the real ones give: android_mocks.h's GMP
# names on mini-gmp (mpz_fits_uint_p's 32-bit bound, mpz_div_2exp's floor,
# mpz_nextprime, mpz_fib_ui) and execinfo.h's backtrace_symbols, whose array
# upstream's freeList.c reads at strs[1]. A wrong stand-in gave FACTORS wrong
# factors and turned upstream's bad-free message into a crash.
#
# native_stand_ins_contract_test.c is built with the host C compiler under
# AddressSanitizer and UBSan, with android_mocks.h forced into the unit as the app
# build forces it, and linked with the app's mini-gmp. Pure host test; needs cc.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
GLUE="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
MINI_GMP="$PROJECT_ROOT/android/compat/mini-gmp-fallback"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc -std=gnu17 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all \
    -w -c -I"$MINI_GMP" -o "$work/mini-gmp.o" "$MINI_GMP/mini-gmp.c"
cc -std=gnu17 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all \
    -Wall -Wextra -Werror -DHOST_TOOL_BUILD -I"$GLUE" -I"$MINI_GMP" \
    -include "$GLUE/android_mocks.h" \
    -o "$work/stand_ins" "$SCRIPT_DIR/native_stand_ins_contract_test.c" "$work/mini-gmp.o"
output="$("$work/stand_ins" 2>&1)" ||
    contract_fail "a stand-in differs from what it replaces:" "$output"

contract_pass "the glue's GMP and execinfo stand-ins give what GMP and glibc give on every boundary case."
