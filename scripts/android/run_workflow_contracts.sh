#!/bin/bash

# Runs every pure-host CI contract guard in sequence, folding each into its own
# GitHub log group and naming its result, so the linux-ci host-workload
# -regressions job needs one step instead of one per contract. Every contract is
# run even after a failure, so a single CI run reports all of them; the script
# exits non-zero if any failed.
#
# The lcd HAL contract is intentionally not listed here -- it builds a C harness
# and tees its own log, so it stays a separate step. Pure host, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONTRACTS=(
    run_ci_contract_lib_meta_test.sh
    run_ndk_resolution_contract.sh
    run_release_abi_single_source_contract.sh
    run_published_artifacts_verifier_contract.sh
    run_production_signing_scope_contract.sh
    run_release_provenance_contract.sh
    run_privileged_remote_script_contract.sh
    run_llvm_toolchain_install_contract.sh
    run_build_deps_single_source_contract.sh
)

failed=()
for contract in "${CONTRACTS[@]}"; do
    echo "::group::${contract}"
    if bash "$SCRIPT_DIR/$contract"; then
        echo "PASS: ${contract}"
    else
        echo "FAIL: ${contract}" >&2
        failed+=("$contract")
    fi
    echo "::endgroup::"
done

if [ "${#failed[@]}" -ne 0 ]; then
    echo "FAIL: ${#failed[@]} host CI contract(s) failed: ${failed[*]}" >&2
    exit 1
fi

echo "OK: all ${#CONTRACTS[@]} host CI contracts passed."
