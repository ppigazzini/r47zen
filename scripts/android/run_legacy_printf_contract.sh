#!/bin/bash
# Contract: on Android 7.0 to 8.1 the library formats the core's text (the
# minSdk row aborted in showStep on API 24 without it).
#
# legacy_printf_contract_test.c builds r47zen/legacy_printf.c with the host C
# compiler, under AddressSanitizer and UBSan, and holds r47_bytes_vsnprintf to
# glibc's vsnprintf, which scans bytes as Android 9 does, on formats carrying the
# core's glyph bytes, at every buffer size from none to ample, and checks that only
# a format with a byte over 0x7f is formatted here. Then the wiring: the app's
# CMakeLists wraps each sprintf and snprintf form, the glue lists the file, and each
# v-form wrapper leaves an ASCII format, and every call from API 28, to the
# platform. Pure host test; needs cc.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
GLUE="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cc -std=gnu11 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all \
    -Wall -Wextra -Werror -Wno-format-nonliteral -I"$GLUE" \
    -o "$work/legacy_printf_test" "$SCRIPT_DIR/legacy_printf_contract_test.c" "$GLUE/legacy_printf.c"
output="$("$work/legacy_printf_test" 2>&1)" ||
    contract_fail "the legacy printf host test failed:" "$output"

cmake="$(strip_hash_comments <"$PROJECT_ROOT/android/app/src/main/cpp/CMakeLists.txt")"
for entry in sprintf snprintf vsprintf vsnprintf __sprintf_chk __snprintf_chk __vsprintf_chk __vsnprintf_chk; do
    grep -qE "^[[:space:]]*__wrap_${entry}\(|^int __wrap_${entry}\(" "$GLUE/legacy_printf.c" ||
        contract_fail "legacy_printf.c defines no __wrap_${entry}"
    grep -qE "(ITEMS|[[:space:]])${entry}([[:space:]]|\)|$)" <<<"$cmake" ||
        contract_fail "the app's CMakeLists does not wrap ${entry}"
done
# shellcheck disable=SC2016 # CMake's own ${} expression, matched unexpanded
grep -qF -- '-Wl,--wrap=${R47_PRINTF_ENTRY}' <<<"$cmake" ||
    contract_fail "the app's CMakeLists does not link the printf wraps"
grep -qx 'legacy_printf.c' "$GLUE/glue_sources.txt" ||
    contract_fail "glue_sources.txt does not list legacy_printf.c"
grep -qF 'return level < 28;' "$GLUE/legacy_printf.c" ||
    contract_fail "legacy_printf.c no longer hands the platform its calls from API 28"
grep -qF 'return platformReadsUtf8() && r47_format_has_high_bytes(format);' "$GLUE/legacy_printf.c" ||
    contract_fail "legacy_printf.c no longer leaves an ASCII format to the platform"
gates="$(grep -c 'if (!formatsHere(format)) {\|return formatsHere(format)' "$GLUE/legacy_printf.c")"
[ "$gates" -eq 4 ] ||
    contract_fail "each of the four v-form wrappers must ask formatsHere(format); $gates do"

contract_pass "on Android 7.0 to 8.1 the library formats a format holding the core's glyph bytes as glibc does, and leaves every other format, and API 28 and later, to the platform."
