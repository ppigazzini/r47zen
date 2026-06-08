#!/bin/bash

# Contract: the release/CI workflows must source the verification ABI from
# R47_DEFAULT_ANDROID_ABI_FILTERS, never hardcode it. The published library is
# built with abiFilters derived from that default, so a hardcoded
# --expected-abis / --expected-apk-abis in a workflow could drift from the ABI
# actually built and silently verify the wrong (or no) ABI.
#
# This guard fails if any workflow passes a literal ABI to those flags instead
# of a ${{ ... }} reference / shell variable. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Lines passing an --expected-abis / --expected-apk-abis flag whose value begins
# with an alphanumeric character are hardcoded literals (a ${{ ... }} reference
# or a "$var" begins with '$', a quoted "$var" with '"'). workflow_grep excludes
# comment lines.
violations="$(workflow_grep '--expected(-apk)?-abis[[:space:]]+[A-Za-z0-9]' || true)"

if [ -n "$violations" ]; then
    contract_fail \
        "hardcoded ABI literals found; source them from R47_DEFAULT_ANDROID_ABI_FILTERS:" \
        "$violations"
fi

contract_pass "no workflow hardcodes an --expected-abis / --expected-apk-abis literal."
