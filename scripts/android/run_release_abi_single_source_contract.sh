#!/bin/bash

# Contract: the release/CI workflows must source the verification ABI from
# R47_DEFAULT_ANDROID_ABI_FILTERS, never hardcode it. The published library is
# built with abiFilters derived from that default, so a hardcoded
# --expected-abis / --expected-apk-abis in a workflow could drift from the ABI
# actually built and silently verify the wrong (or no) ABI.
#
# This guard fails if any workflow, composite, or script passes a literal ABI to
# those flags instead of a ${{ ... }} reference / shell variable, a quoted one
# included. Leaving the flag out is refused by collect_packaging_evidence.sh
# itself (run_evidence_abi_contract.sh). Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Lines passing an --expected-abis / --expected-apk-abis flag whose value begins
# with an alphanumeric character are hardcoded literals (a ${{ ... }} reference
# or a "$var" begins with '$', a quoted "$var" with '"'). workflow_grep excludes
# comment lines.
pattern='--expected(-apk)?-abis[[:space:]]+"?(arm64|armeabi|x86|mips|riscv)'
violations="$(workflow_grep "$pattern" || true)"
violations="${violations}$(scripts_grep "$pattern" "$(basename "${BASH_SOURCE[0]}")" run_evidence_abi_contract.sh || true)"

if [ -n "$violations" ]; then
    contract_fail \
        "hardcoded ABI literals found; source them from R47_DEFAULT_ANDROID_ABI_FILTERS:" \
        "$violations"
fi

contract_pass "no workflow hardcodes an --expected-abis / --expected-apk-abis literal."
