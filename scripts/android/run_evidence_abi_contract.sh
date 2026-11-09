#!/bin/bash
# Contract: packaging evidence holds an artifact to the ABIs it must carry, the
# bundle included, and refuses to run without them.
#
# collect_packaging_evidence.sh skipped its ABI check when --expected-abis was
# left out, and checked the APK's lib/ alone, never the bundle Play receives.
# It now requires the flag for both and checks base/lib/ too.
# This runs the real script on a fixture bundle, which needs no SDK: the right
# ABI passes, a wrong or extra one fails, and a run without the flag fails.
# run_release_abi_single_source_contract.sh holds every caller to a variable.
# Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
COLLECT="$SCRIPT_DIR/collect_packaging_evidence.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

bundle() {
    local name="$1"
    shift
    rm -rf "$work/tree"
    mkdir -p "$work/tree/base/assets"
    for asset in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json; do
        echo "$asset" >"$work/tree/base/assets/$asset"
    done
    printf 'int r47_entry(void) { return 0; }\n' >"$work/lib.c"
    cc -shared -fPIC -o "$work/lib.so" "$work/lib.c"
    for abi in "$@"; do
        mkdir -p "$work/tree/base/lib/$abi"
        cp "$work/lib.so" "$work/tree/base/lib/$abi/libr47zen.so"
    done
    (cd "$work/tree" && zip -qr "$work/$name.aab" .)
}

collect() {
    local name="$1"
    shift
    rm -rf "$work/out-$name"
    bash "$COLLECT" --variant release --bundle "$work/$name.aab" \
        --output-dir "$work/out-$name" "$@" >"$work/log-$name" 2>&1
}

bundle good arm64-v8a
collect good --expected-abis arm64-v8a ||
    contract_fail "the evidence refused a bundle with the expected ABI:" "$(cat "$work/log-good")"
grep -qx arm64-v8a "$work/out-good/abis.txt" ||
    contract_fail "the bundle's evidence does not record its ABIs"
if collect good --expected-abis x86_64; then
    contract_fail "the evidence passed a bundle without the expected ABI"
fi
bundle extra arm64-v8a x86_64
if collect extra --expected-abis arm64-v8a; then
    contract_fail "the evidence passed a bundle with an ABI it must not carry"
fi
if collect good; then
    contract_fail "the evidence ran with no --expected-abis, so it checked no ABI"
fi

contract_pass "packaging evidence requires --expected-abis and checks the APK's and the bundle's libraries against it."
