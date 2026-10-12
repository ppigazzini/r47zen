#!/bin/bash

# Contract: a core-encoded keypad label copied into a fixed buffer is cut only
# between glyphs. In the core's encoding a byte with bit 7 set opens a two-byte
# glyph; snprintf cuts by bytes, so it can keep a lead byte and drop its trail,
# and stringToUtf8 then exports one stray code point.
#
# Two halves, both pure host (no SDK, no staged native tree):
#   1. Compile and run core_label_copy_contract_test.c against the real
#      core_label.h, pinning r47_copy_core_label on every buffer size over a
#      label that mixes one- and two-byte glyphs.
#   2. Fail when keypad_scene.c copies a string with a bare snprintf "%s"
#      anywhere but setUtf8Label, which copies UTF-8, not the core encoding;
#      the pattern first proves itself on a seeded line.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
BUILD_DIR="${R47_CORE_LABEL_CONTRACT_BUILD_DIR:-$PROJECT_ROOT/android/build/core-label-copy-contract}"
CC_BIN="${CC:-cc}"

[ -f "$TRACKED_CPP_DIR/core_label.h" ] ||
    contract_fail "missing $TRACKED_CPP_DIR/core_label.h (the core label copy helper)."

mkdir -p "$BUILD_DIR"
output="$BUILD_DIR/core-label-copy-contract"
"$CC_BIN" -std=gnu17 -O0 -g -Wall -Wextra -Werror \
    -I"$TRACKED_CPP_DIR" \
    "$SCRIPT_DIR/core_label_copy_contract_test.c" \
    -o "$output"
"$output"

# Print each line of FILE that copies a string with a bare snprintf "%s" and is
# not setUtf8Label's UTF-8 copy.
bare_string_copies() {
    grep -nE 'snprintf\([^"]*"%s",' "$1" | grep -vE 'snprintf\(utf8, utf8Size, "%s",' || true
}

fixture="$(mktemp)"
trap 'rm -f "$fixture"' EXIT
printf '%s\n' '  snprintf(scene->primaryLabel, sizeof(scene->primaryLabel), "%s", itemName);' \
    '  snprintf(utf8, utf8Size, "%s", value ? value : "");' >"$fixture"
[ "$(bare_string_copies "$fixture" | grep -c .)" = 1 ] ||
    contract_fail "the copy checker did not flag exactly the seeded core-label snprintf."

copies="$(bare_string_copies "$TRACKED_CPP_DIR/keypad_scene.c")"
[ -z "$copies" ] ||
    contract_fail "keypad_scene.c copies a core label by bytes; use r47_copy_core_label:" "$copies"

contract_pass "core keypad labels are cut on a glyph boundary, and keypad_scene.c copies them only through r47_copy_core_label."
