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
#      raw deadline comparison in either operand order; the patterns first
#      prove themselves on seeded lines.

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
# operator (<, <=, >, >=), in either operand order, between a scheduler deadline
# (nextTimerRefresh, nextScreenRefresh, next_due, the mock-timer fields) and the
# current clock or another deadline.
deadline_ids='(nextTimerRefresh|nextScreenRefresh|next_due|next_fire(_ms)?|g_android_mock[A-Za-z0-9_.]*)'
clock_ids='(now|sys_current_ms\(\))'
raw_patterns=(
    "${deadline_ids}[[:space:]]*[<>]=?[[:space:]]*${clock_ids}([^A-Za-z0-9_]|$)"
    "(^|[^A-Za-z0-9_])${clock_ids}[[:space:]]*[<>]=?[[:space:]]*${deadline_ids}"
    "${deadline_ids}[[:space:]]*[<>]=?[[:space:]]*${deadline_ids}"
)

# Prove the patterns catch every operand order, and pass the helper calls and
# plain assignments the real sources use.
for seeded in 'if (now >= next_due)' 'if (nextTimerRefresh > now)' \
    'if (sys_current_ms() >= nextScreenRefresh)' 'if (next_fire_ms <= now)' \
    'if (nextScreenRefresh < next_due)' 'if (now < g_android_mock_timeout.next_fire_ms)' \
    'return next_due>now;'; do
    matched=false
    for pattern in "${raw_patterns[@]}"; do
        grep -Eq -- "$pattern" <<<"$seeded" && matched=true
    done
    [ "$matched" = true ] || fail "the raw-comparison patterns miss the seeded '$seeded'."
done
for allowed in 'if (r47_ms_deadline_reached(now, next_due))' 'nextTimerRefresh = now + 5;' \
    'uint32_t now = sys_current_ms();' 'if (r47_ms_before(nextScreenRefresh, next_due))' \
    'if (nowPlaying > limit)'; do
    for pattern in "${raw_patterns[@]}"; do
        if grep -Eq -- "$pattern" <<<"$allowed"; then
            fail "the raw-comparison pattern '$pattern' rejects the allowed '$allowed'."
        fi
    done
done

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
