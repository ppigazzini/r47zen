#!/bin/bash
# Contract: the native library exports only its JNI entry points.
#
# check_native_exports.sh is run on shared objects built here with the host C
# compiler: one linked with the app's own r47zen/exports.map passes, though it
# defines a helper and a global, and keeps JNI_OnLoad and a Java_ function
# exported; the same sources linked without the map fail and name the helper.
# Then the wiring: the app's CMakeLists links the map, check_native_hardening.sh
# runs the check, and the map keeps exactly JNI_OnLoad and Java_* global.
# Pure host test; needs cc and binutils.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
CHECK="$SCRIPT_DIR/check_native_exports.sh"
MAP="$PROJECT_ROOT/android/app/src/main/cpp/r47zen/exports.map"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat >"$work/lib.c" <<'SRC'
int counter = 0;
int helper(int value) { return value + counter; }
int JNI_OnLoad(void *vm, void *reserved) { (void)vm; (void)reserved; return helper(0x10006); }
int Java_io_github_ppigazzini_r47zen_CalculatorEngine_isReadyNative(void) { return helper(1); }
SRC
cc -shared -fPIC -o "$work/mapped.so" "$work/lib.c" "-Wl,--version-script=$MAP"
cc -shared -fPIC -o "$work/open.so" "$work/lib.c"

bash "$CHECK" "$work/mapped.so" >/dev/null ||
    contract_fail "check_native_exports.sh refused a library linked with exports.map"
dyn="$(readelf --dyn-syms -W "$work/mapped.so")"
for name in JNI_OnLoad Java_io_github_ppigazzini_r47zen_CalculatorEngine_isReadyNative; do
    grep -qw "$name" <<<"$dyn" || contract_fail "exports.map hid the JNI entry point $name"
done
if output="$(bash "$CHECK" "$work/open.so" 2>&1)"; then
    contract_fail "check_native_exports.sh passed a library exporting a helper"
fi
grep -qw helper <<<"$output" || contract_fail "the refusal does not name the extra export:" "$output"

cmake="$(strip_hash_comments <"$PROJECT_ROOT/android/app/src/main/cpp/CMakeLists.txt")"
# shellcheck disable=SC2016 # CMake's own ${} expression, matched unexpanded
if ! grep -qF -- '-Wl,--version-script=${R47_EXPORTS_MAP}' <<<"$cmake" ||
    ! grep -qF 'r47zen/exports.map' <<<"$cmake"; then
    contract_fail "the app's CMakeLists does not link r47zen/exports.map"
fi
hardening="$(strip_hash_comments <"$PROJECT_ROOT/scripts/security/check_native_hardening.sh")"
grep -qF 'check_native_exports.sh' <<<"$hardening" ||
    contract_fail "check_native_hardening.sh does not run the export check"
# The map's text after its opening comment.
map_body="$(awk 'past { print } /\*\// { past = 1 }' "$MAP" | tr -d ' \n')"
[ "$map_body" = '{global:JNI_OnLoad;Java_*;local:*;};' ] ||
    contract_fail "exports.map keeps more or less than JNI_OnLoad and Java_* global: $map_body"

contract_pass "the native library exports only JNI_OnLoad and its Java_ entry points."
