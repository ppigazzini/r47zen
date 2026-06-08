#!/bin/bash

# Contract: the production release must publish a SLSA build-provenance
# attestation for the artifacts it ships AND verify it in the same run. The
# publish-production-release job in android-release.yml must therefore declare
# the id-token:write and attestations:write permissions, run
# actions/attest-build-provenance over the published APK and AAB, and verify the
# result with `gh attestation verify`. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# The publish-production-release job block (header to the next job or EOF).
block="$(workflow_job_block "$WORKFLOW_DIR/android-release.yml" publish-production-release)"

missing=""
grep -q 'id-token: write' <<<"$block" || missing="$missing id-token:write"
grep -q 'attestations: write' <<<"$block" || missing="$missing attestations:write"
grep -q 'uses: actions/attest-build-provenance@' <<<"$block" || missing="$missing actions/attest-build-provenance"
# Require a real `gh attestation verify` command, not just a mention in a
# comment (strip_yaml_comments), so the attestation is re-verified in the run.
strip_yaml_comments <<<"$block" | grep -q 'gh attestation verify' ||
    missing="$missing gh-attestation-verify"

if [ -n "$missing" ]; then
    contract_fail "publish-production-release does not attest and verify build provenance; missing:${missing}"
fi

contract_pass "publish-production-release attests and verifies build provenance for the published artifacts."
