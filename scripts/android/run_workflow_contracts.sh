#!/bin/bash

# Run every pure-host CI contract guard in sequence, folding each into its own
# GitHub log group and naming its result, so the linux-ci host-workload
# -regressions job needs one step instead of one per contract. Every contract is
# run even after a failure, so a single CI run reports all of them; the script
# exits non-zero if any failed.
#
# Every run_*_contract.sh in this directory is in CONTRACTS but those in
# STAGED_TREE_CONTRACTS, which need the staged native tree and run as steps of
# their own in linux-ci.yml; the run fails on a contract in neither, which would
# otherwise never run with nothing to say so. Pure host, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

STAGED_TREE_CONTRACTS=(
    run_hal_io_contract.sh
    run_keypad_generation_contract.sh
)

CONTRACTS=(
    run_ci_contract_lib_meta_test.sh
    run_ndk_resolution_contract.sh
    run_release_abi_single_source_contract.sh
    run_published_artifacts_verifier_contract.sh
    run_previous_release_contract.sh
    run_version_naming_contract.sh
    run_production_signing_scope_contract.sh
    run_signing_isolation_contract.sh
    run_release_dry_run_contract.sh
    run_green_ci_pair_contract.sh
    run_log_upload_contract.sh
    run_compliance_assets_contract.sh
    run_dependency_verification_contract.sh
    run_stale_release_assets_contract.sh
    run_evidence_abi_contract.sh
    run_shipped_symbols_contract.sh
    run_shipped_build_smoke_contract.sh
    run_core_source_archive_contract.sh
    run_host_tools_record_contract.sh
    run_native_exports_contract.sh
    run_legacy_printf_contract.sh
    run_native_stand_ins_contract.sh
    run_actions_lock_coverage_contract.sh
    run_core_source_list_contract.sh
    run_release_provenance_contract.sh
    run_privileged_remote_script_contract.sh
    run_llvm_toolchain_install_contract.sh
    run_build_deps_single_source_contract.sh
    run_toolchain_pin_coherence_contract.sh
    run_build_jdk_pin_coherence_contract.sh
    run_action_pin_consistency_contract.sh
    run_setup_android_packages_contract.sh
    run_setup_android_composite_contract.sh
    run_gradle_cache_contract.sh
    run_bridge_tsan_lane_contract.sh
    run_lock_free_signal_atomicity_contract.sh
    run_mini_gmp_accounting_contract.sh
    run_core_label_copy_contract.sh
    run_program_load_bridge_default_contract.sh
    run_test_integrity_contract.sh
    run_privacy_policy_single_source_contract.sh
    run_manual_site_contract.sh
    run_commit_message_contract.sh
    run_quiet_grep_pipeline_contract.sh
    run_jni_surface_check_contract.sh
    run_upstream_hal_contract.sh
    run_glue_sources_contract.sh
    run_merge_request_watch_contract.sh
    run_build_metadata_reader_contract.sh
    run_core_language_flags_contract.sh
    run_upstream_sync_contract.sh
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

unlisted="$(LC_ALL=C comm -23 \
    <(find "$SCRIPT_DIR" -maxdepth 1 -name 'run_*_contract.sh' -printf '%f\n' | LC_ALL=C sort) \
    <(printf '%s\n' "${CONTRACTS[@]}" "${STAGED_TREE_CONTRACTS[@]}" | LC_ALL=C sort))"
if [ -n "$unlisted" ]; then
    echo "FAIL: contracts in $SCRIPT_DIR that neither list names, so nothing runs them:" >&2
    sed 's/^/  /' <<<"$unlisted" >&2
    failed+=("(unlisted contracts)")
fi

if [ "${#failed[@]}" -ne 0 ]; then
    echo "FAIL: ${#failed[@]} host CI contract(s) failed: ${failed[*]}" >&2
    exit 1
fi

echo "OK: all ${#CONTRACTS[@]} host CI contracts passed, and every contract on disk is listed."
