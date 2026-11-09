#!/bin/bash
# Contract: packaging evidence refuses a shipped library that exports the test
# bridge or a `main`.
#
# check_shipped_symbols.sh is run on shared objects built here with the host C
# compiler: a clean one passes; one exporting a Java_..._ProgramLoadTestBridge_
# function, an r47_test_bridge_ function, or `main` fails; one that only calls an
# undefined bridge function passes, since it exports nothing. Then
# collect_packaging_evidence.sh runs it on every library of the APK and the
# bundle. Pure host test; needs cc and binutils.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
CHECK="$SCRIPT_DIR/check_shipped_symbols.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

library() {
    local name="$1" source="$2"
    printf '%s\n' "$source" >"$work/$name.c"
    cc -shared -fPIC -o "$work/$name.so" "$work/$name.c"
}

library clean 'int Java_io_github_ppigazzini_r47zen_CalculatorEngine_sendKey(void) { return 0; }'
library bridge 'int Java_io_github_ppigazzini_r47zen_ProgramLoadTestBridge_seedXNative(void) { return 0; }'
library publisher 'void r47_test_bridge_publish_state(void) {}'
library program 'int main(void) { return 0; }'
library caller 'extern void r47_test_bridge_publish_state(void); void run(void) { r47_test_bridge_publish_state(); }'

bash "$CHECK" "$work/clean.so" "$work/caller.so" >/dev/null 2>&1 ||
    contract_fail "check_shipped_symbols.sh refused a library that exports no test entry point"
for name in bridge publisher program; do
    if bash "$CHECK" "$work/$name.so" >/dev/null 2>&1; then
        contract_fail "check_shipped_symbols.sh passed a library exporting the $name entry point"
    fi
done

evidence="$(strip_hash_comments <"$SCRIPT_DIR/collect_packaging_evidence.sh")"
[ "$(grep -c 'check_shipped_symbols.sh' <<<"$evidence")" -ge 2 ] ||
    contract_fail "collect_packaging_evidence.sh does not check the libraries of both the APK and the bundle"

contract_pass "packaging evidence refuses a shipped library exporting the test bridge or a main."
