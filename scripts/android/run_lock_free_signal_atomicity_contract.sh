#!/bin/bash

# Contract: every lock-free cross-thread display/refresh signal in the Android
# bridge stays a C11 atomic and is only ever touched through an atomic call.
# These signals are written by the core thread and sampled with no lock held by
# the UI/JNI threads, so plain volatile, or a plain read or write of the atomic,
# leaves the concurrent access a data race under the C memory model
# (ThreadSanitizer flags it via build_bridge_tsan_harness.sh).
#
# For each signal:
# - its definition is _Atomic, never volatile;
# - every use in the Android-owned glue (r47zen/**/*.c, *.h), comments aside, is
#   a declaration or an atomic_*(&signal, ...) call, so neither `if (signal)`
#   nor `signal = true` nor `++signal` passes.
#
# The use-site checker first proves it fails on seeded fixtures, then reads the
# real sources. Pure host, no SDK, no staged native tree, no sanitizer build.

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

# The lock-free signals, and the file that defines each.
declare -A SIGNAL_HOME=(
    [lcdBufferDirty]="$LCD_C"
    [packedDisplayGeneration]="$LCD_C"
    [keypadSnapshotGeneration]="$LCD_C"
    [g_r47_stop_refresh_pending]="$RUNTIME_C"
)

# Print FILE with C comments removed, keeping line numbers ("N:text").
strip_c_comments() {
    awk '
        {
            line = $0; out = ""
            while (line != "") {
                if (in_block) {
                    end = index(line, "*/")
                    if (end == 0) { line = ""; break }
                    line = substr(line, end + 2); in_block = 0
                    continue
                }
                open = index(line, "/*"); slash = index(line, "//")
                if (slash && (!open || slash < open)) { out = out substr(line, 1, slash - 1); line = ""; break }
                if (open) { out = out substr(line, 1, open - 1); line = substr(line, open + 2); in_block = 1; continue }
                out = out line; line = ""
            }
            print NR ":" out
        }
    ' "$1"
}

# Print "file:line: text" for each use of SIGNAL in FILE that is neither a
# declaration nor the &SIGNAL argument of an atomic_* call.
plain_signal_uses() {
    local file="$1" sig="$2"
    strip_c_comments "$file" | awk -v sig="$sig" -v file="${file#"$PROJECT_ROOT/"}" '
        function count(text, re,    n) { n = 0; while (match(text, re)) { n++; text = substr(text, RSTART + RLENGTH) } return n }
        {
            sep = index($0, ":"); text = substr($0, sep + 1)
            word = "(^|[^A-Za-z0-9_])" sig "([^A-Za-z0-9_]|$)"
            if (text !~ word) next
            if (text ~ ("_Atomic[ \t]+[A-Za-z0-9_]+[ \t]+" sig "([^A-Za-z0-9_]|$)")) next
            uses = count(text, word)
            atomic = count(text, "atomic_[a-z_]+\\([ \t]*&[ \t]*" sig "([^A-Za-z0-9_]|$)")
            if (uses != atomic) print file ":" substr($0, 1, sep - 1) ": " text
        }
    '
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
    grep -Eq "_Atomic[[:space:]]+[A-Za-z0-9_]+[[:space:]]+$sig\\b" "$home" ||
        fail "${home#"$PROJECT_ROOT/"}: lock-free signal '$sig' is not declared _Atomic."
    if grep -Eq "volatile[[:space:]]+[A-Za-z0-9_]+[[:space:]]+$sig\\b" "$home"; then
        fail "${home#"$PROJECT_ROOT/"}: lock-free signal '$sig' regressed to volatile."
    fi
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
