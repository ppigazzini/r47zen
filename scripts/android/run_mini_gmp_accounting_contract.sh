#!/bin/bash

# Contract: the vendored Android mini-gmp fallback keeps the size-prefix header
# that makes the c47 GMP accounting hooks (freeGmp / reallocGmp) receive the real
# block size. Upstream mini-gmp passes size 0 to those hooks, which leaves the
# core's gmpMemInBytes counter monotonically increasing; on the Android build
# that defines PC_BUILD the upstream items.c "gmpMemInBytes should be 0"
# self-check then fires (sprintf + fprintf + fflush) on every operation in the
# shipping app. A mini-gmp re-vendor that drops the header would silently bring
# the per-operation diagnostic spam back, so this guards both that the broken
# size-0 free is gone and that the header accounting is present.
#
# Pure-host text check: no SDK, no staged native tree, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MINI_GMP="$PROJECT_ROOT/android/compat/mini-gmp-fallback/mini-gmp.c"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[ -f "$MINI_GMP" ] || fail "missing required file: ${MINI_GMP#"$PROJECT_ROOT/"}"

# The broken upstream form must not return: a free that hands size 0 to the hook.
if grep -Eq 'gmp_free_func\)[[:space:]]*\(\([^,]*\),[[:space:]]*0[[:space:]]*\)' "$MINI_GMP"; then
    fail "mini-gmp.c free hook passes size 0 again (breaks gmpMemInBytes accounting)."
fi

# The realloc hook must not pass old size 0 either.
if grep -Eq 'gmp_reallocate_func\)[[:space:]]*\([^,]*,[[:space:]]*0[[:space:]]*,' "$MINI_GMP"; then
    fail "mini-gmp.c realloc hook passes old size 0 again (breaks gmpMemInBytes accounting)."
fi

# The size-prefix header accounting must be present.
grep -q 'R47_GMP_HDR' "$MINI_GMP" ||
    fail "mini-gmp.c lost the R47_GMP_HDR size-prefix accounting header."
grep -Eq 'old_total|new_total' "$MINI_GMP" ||
    fail "mini-gmp.c realloc no longer carries the real old/new block size."

echo "OK: mini-gmp keeps the size-prefix accounting header (no size-0 free/realloc)."
