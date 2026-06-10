#!/bin/bash

# Contract: the Android-native scheduler must compare millisecond deadlines
# wrap-safely. sys_current_ms() truncates CLOCK_MONOTONIC to uint32_t (wraps
# every ~49.7 days of device awake time); a raw "deadline <= now" comparison
# stalls every pending refresh deadline for the rest of the wrap period the
# moment now wraps past a not-yet-due deadline.
#
# Two halves, both pure host (no SDK, no staged native tree):
#   1. Compile and run wrap_safe_time_contract_test.c against the real
#      r47_time.h, pinning the helper semantics across the wrap and the
#      "deadline 0 is unset / due immediately" sentinel.
#   2. Assert the deadline-bearing sources (jni_lifecycle.c,
#      android_runtime.c) include r47_time.h, use the helpers, and carry no
#      raw deadline comparison.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
BUILD_DIR="${R47_WRAP_TIME_CONTRACT_BUILD_DIR:-$PROJECT_ROOT/android/build/wrap-safe-time-contract}"
CC_BIN="${CC:-cc}"

fail() {
    echo "FAIL: $1" >&2
    shift || true
    if [ "$#" -gt 0 ]; then
        printf '%s\n' "$@" >&2
    fi
    exit 1
}

[ -f "$TRACKED_CPP_DIR/r47_time.h" ] ||
    fail "missing $TRACKED_CPP_DIR/r47_time.h (the wrap-safe time helpers)."

mkdir -p "$BUILD_DIR"
output="$BUILD_DIR/wrap-safe-time-contract"

"$CC_BIN" -std=c99 -O0 -g -Wall -Werror \
    -I"$TRACKED_CPP_DIR" \
    "$SCRIPT_DIR/wrap_safe_time_contract_test.c" \
    -o "$output"

echo "--- Running wrap-safe time helper contract test ---"
"$output"

echo "--- Checking deadline sources use the wrap-safe helpers ---"
deadline_sources=(
    "$TRACKED_CPP_DIR/jni_lifecycle.c"
    "$TRACKED_CPP_DIR/android_runtime.c"
)

# Raw uint32 deadline comparisons this contract forbids: any relational
# operator between the scheduler deadlines (nextTimerRefresh,
# nextScreenRefresh, next_due, the mock-timer fields) and the current clock.
raw_patterns=(
    '(nextTimerRefresh|nextScreenRefresh|next_due)[[:space:]]*<=?[[:space:]]*now'
    'sys_current_ms\(\)[[:space:]]*>='
    'now[[:space:]]*<[[:space:]]*g_android_mock'
    'next_fire(_ms)?[[:space:]]*(<=|>)[[:space:]]*now'
    'nextScreenRefresh[[:space:]]*<[[:space:]]*next_due'
)

for source_file in "${deadline_sources[@]}"; do
    [ -f "$source_file" ] || fail "missing deadline source $source_file"

    grep -q '#include "r47_time.h"' "$source_file" ||
        fail "$source_file does not include r47_time.h."

    grep -Eq 'r47_ms_(deadline_reached|until_deadline|before)' "$source_file" ||
        fail "$source_file does not use the wrap-safe deadline helpers."

    for pattern in "${raw_patterns[@]}"; do
        if violations="$(grep -nE -- "$pattern" "$source_file")"; then
            fail "raw (wrap-unsafe) deadline comparison in $source_file:" \
                "$violations"
        fi
    done
done

echo "OK: wrap-safe time helpers verified and all deadline sites use them."
