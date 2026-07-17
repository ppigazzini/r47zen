#!/bin/bash

# Contract: every lock-free cross-thread display/refresh signal in the Android
# bridge stays a C11 atomic and never regresses to plain volatile. These signals
# are written under a mutex by the core thread and sampled with no lock held by
# the UI/JNI threads, so plain volatile leaves the concurrent access a data race
# under the C memory model (ThreadSanitizer flags it via
# build_bridge_tsan_harness.sh). The three packed-display signals in hal/lcd.c are
# C11 atomics and this guard extends the same rule to the
# stop-refresh request flag in android_runtime.c, so a later edit that quietly
# reintroduces `volatile` for any of them is caught fast in
# run_workflow_contracts.sh rather than only by a live sanitizer run.
#
# Pure-host text check: no SDK, no staged native tree, no sanitizer build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
LCD_C="$CPP_DIR/hal/lcd.c"
RUNTIME_C="$CPP_DIR/android_runtime.c"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

for f in "$LCD_C" "$RUNTIME_C"; do
    [ -f "$f" ] || fail "missing required file: ${f#"$PROJECT_ROOT/"}"
done

# The lock-free signals, by the identifier each is declared with.
lcd_signals=(lcdBufferDirty packedDisplayGeneration keypadSnapshotGeneration)

for sig in "${lcd_signals[@]}"; do
    grep -Eq "_Atomic[[:space:]]+[A-Za-z0-9_]+[[:space:]]+$sig\\b" "$LCD_C" ||
        fail "hal/lcd.c: lock-free signal '$sig' is not declared _Atomic."
    grep -Eq "volatile[[:space:]]+[A-Za-z0-9_]+[[:space:]]+$sig\\b" "$LCD_C" &&
        fail "hal/lcd.c: lock-free signal '$sig' regressed to volatile."
done

# The stop-refresh request flag is the same shape: a JNI-thread writer with no
# lock vs. a core-thread read-and-clear. It must be _Atomic, never volatile, and
# must not be touched with a plain (non-atomic) assignment.
grep -Eq '_Atomic[[:space:]]+bool[[:space:]]+g_r47_stop_refresh_pending\b' "$RUNTIME_C" ||
    fail "android_runtime.c: g_r47_stop_refresh_pending is not declared _Atomic bool."
grep -Eq 'volatile[[:space:]]+[A-Za-z0-9_]+[[:space:]]+g_r47_stop_refresh_pending\b' "$RUNTIME_C" &&
    fail "android_runtime.c: g_r47_stop_refresh_pending regressed to volatile."

# A plain assignment (`g_r47_stop_refresh_pending = ...`) bypasses the atomic
# store; the access must go through atomic_store/atomic_exchange. The declaration
# initializer (`= false`) is allowed; reject any *other* bare assignment.
bad_assign="$(grep -nE 'g_r47_stop_refresh_pending[[:space:]]*=' "$RUNTIME_C" |
    grep -vE '_Atomic[[:space:]]+bool[[:space:]]+g_r47_stop_refresh_pending[[:space:]]*=[[:space:]]*false' || true)"
if [ -n "$bad_assign" ]; then
    fail "android_runtime.c: g_r47_stop_refresh_pending has a non-atomic assignment: $bad_assign"
fi

echo "OK: lock-free cross-thread signals stay C11 atomics (no volatile regression)."
