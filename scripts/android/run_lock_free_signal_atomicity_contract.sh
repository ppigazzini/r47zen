#!/bin/bash

# Contract: every lock-free cross-thread display/refresh signal in the Android
# bridge stays a C11 atomic and is only ever touched through an atomic call.
# These signals are written by the core thread and sampled with no lock held by
# the UI/JNI threads, so plain volatile, or a plain read or write of the atomic,
# leaves the concurrent access a data race under the C memory model
# (ThreadSanitizer flags it via build_bridge_tsan_harness.sh).
#
# For each signal:
# - its definition, comments aside, is _Atomic, never volatile;
# - every use in the Android-owned glue (r47zen/**/*.c, *.h), comments aside, is
#   a declaration or an atomic_*(&signal, ...) call, so neither `if (signal)`
#   nor `signal = true` nor `++signal` passes.
#
# Both checkers first prove they fail on seeded fixtures, then read the real
# sources. Pure host, no SDK, no staged native tree, no sanitizer build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
LCD_C="$CPP_DIR/hal/lcd.c"
RUNTIME_C="$CPP_DIR/android_runtime.c"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# The lock-free signals, and the file that defines each.
declare -A SIGNAL_HOME=(
    [packedDisplayGeneration]="$LCD_C"
    [keypadSnapshotGeneration]="$LCD_C"
    [g_r47_stop_refresh_pending]="$RUNTIME_C"
)

# Print "file:line: text" for each use of SIGNAL in FILE that is neither a
# declaration nor the &SIGNAL argument of an atomic_* call.
plain_signal_uses() {
    local file="$1" sig="$2"
    strip_c_comments "$file" | awk -v sig="$sig" -v file="${file#"$PROJECT_ROOT/"}" '
        function count(text, re,    n) { n = 0; while (match(text, re)) { n++; text = substr(text, RSTART + RLENGTH) } return n }
        {
            text = $0
            word = "(^|[^A-Za-z0-9_])" sig "([^A-Za-z0-9_]|$)"
            if (text !~ word) next
            if (text ~ ("_Atomic[ \t]+[A-Za-z0-9_]+[ \t]+" sig "([^A-Za-z0-9_]|$)")) next
            uses = count(text, word)
            atomic = count(text, "atomic_[a-z_]+\\([ \t]*&[ \t]*" sig "([^A-Za-z0-9_]|$)")
            if (uses != atomic) print file ":" NR ": " text
        }
    '
}

# Print why FILE, comments aside, does not define SIGNAL as an _Atomic that is
# never volatile; print nothing when it does.
signal_definition_problem() {
    local file="$1" sig="$2" live
    live="$(strip_c_comments "$file")"
    if ! grep -Eq "_Atomic[[:space:]]+[A-Za-z0-9_]+[[:space:]]+$sig\\b" <<<"$live"; then
        echo "lock-free signal '$sig' is not declared _Atomic."
    elif grep -Eq "volatile[[:space:]]+[A-Za-z0-9_]+[[:space:]]+$sig\\b" <<<"$live"; then
        echo "lock-free signal '$sig' regressed to volatile."
    fi
}

# --- seeded fixtures ------------------------------------------------------------
fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT
cat >"$fixtures/good.c" <<'C'
_Atomic bool flagSig = false;
extern _Atomic uint32_t flagSig;
/* flagSig = true; is only a comment */
void f(void) {
  // if (flagSig) is a comment too
  atomic_store_explicit(&flagSig, true, memory_order_relaxed);
  if (!atomic_load_explicit(&flagSig, memory_order_relaxed)) {}
  atomic_fetch_add_explicit(&flagSig, 1u, memory_order_relaxed); /* flagSig++ */
}
C
[ -z "$(plain_signal_uses "$fixtures/good.c" flagSig)" ] ||
    fail "the use-site checker flagged the atomic-only fixture: $(plain_signal_uses "$fixtures/good.c" flagSig)"
[ -z "$(signal_definition_problem "$fixtures/good.c" flagSig)" ] ||
    fail "the definition checker rejected the _Atomic fixture: $(signal_definition_problem "$fixtures/good.c" flagSig)"
printf 'bool flagSig = false; // _Atomic bool flagSig\n' >"$fixtures/plain.c"
[ -n "$(signal_definition_problem "$fixtures/plain.c" flagSig)" ] ||
    fail "the definition checker accepted a plain definition that only a comment calls _Atomic."
printf '_Atomic bool flagSig;\nvolatile bool flagSig;\n' >"$fixtures/volatile.c"
[ -n "$(signal_definition_problem "$fixtures/volatile.c" flagSig)" ] ||
    fail "the definition checker accepted a volatile definition."
for bad in 'if (flagSig) {}' 'flagSig = true;' '++flagSig;' 'x = flagSig + 1;' \
    'atomic_store_explicit(&flagSig, flagSig, memory_order_relaxed);' 'volatile_reader(flagSig);'; do
    printf 'void g(void) {\n  %s\n}\n' "$bad" >"$fixtures/bad.c"
    [ -n "$(plain_signal_uses "$fixtures/bad.c" flagSig)" ] ||
        fail "the use-site checker accepted the seeded plain use '$bad'."
done

# --- the real sources ------------------------------------------------------------
for sig in "${!SIGNAL_HOME[@]}"; do
    home="${SIGNAL_HOME[$sig]}"
    [ -f "$home" ] || fail "missing required file: ${home#"$PROJECT_ROOT/"}"
    problem="$(signal_definition_problem "$home" "$sig")"
    [ -z "$problem" ] || fail "${home#"$PROJECT_ROOT/"}: $problem"
done

plain=""
while IFS= read -r -d '' source_file; do
    for sig in "${!SIGNAL_HOME[@]}"; do
        plain+="$(plain_signal_uses "$source_file" "$sig")"
    done
done < <(find "$CPP_DIR" -type f \( -name '*.c' -o -name '*.h' \) -print0)
[ -z "$plain" ] || fail "a lock-free signal is touched without an atomic call:
$plain"

echo "OK: lock-free cross-thread signals stay C11 atomics and every use is an atomic call."
