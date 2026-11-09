#!/bin/bash

# Contract: the production release must publish a SLSA build-provenance
# attestation for the artifacts it ships AND verify it in the same run. The
# publish-production-release job in android-release.yml must therefore declare
# the id-token:write and attestations:write permissions, run actions/attest
# over the published APK and AAB and both packaging-evidence archives, and
# verify each with `gh attestation verify`. It must also attest the SPDX
# inventory the APK ships for the APK and the AAB with actions/attest's
# sbom-path, and verify that with the SPDX 2.3 predicate type.
# Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# The publish-production-release job block (header to the next job or EOF).
block="$(workflow_job_block "$WORKFLOW_DIR/android-release.yml" publish-production-release)"

# Live lines only: a commented-out permission or step must not satisfy a check.
live="$(strip_hash_comments <<<"$block")"
missing=""
grep -q 'id-token: write' <<<"$live" || missing="$missing id-token:write"
grep -q 'attestations: write' <<<"$live" || missing="$missing attestations:write"
[ "$(grep -c 'uses: actions/attest@' <<<"$live")" -ge 2 ] || missing="$missing actions/attest(provenance-and-sbom)"
grep -q 'sbom-path: release-apk/compliance-assets/THIRD-PARTY.spdx.json' <<<"$live" ||
    missing="$missing sbom-path"
grep -q -- '--predicate-type https://spdx.dev/Document/v2.3' <<<"$live" ||
    missing="$missing verify-sbom"
# Require a real `gh attestation verify` command, not just a mention in a
# comment (strip_hash_comments), so the attestation is re-verified in the run.
grep -q 'gh attestation verify' <<<"$live" ||
    missing="$missing gh-attestation-verify"
# The packaging-evidence archives ship on the release too, so each is attested
# and re-verified: its pattern appears once in subject-path and once in the
# verify loop.
for evidence in 'release-apk/*packaging-evidence.zip' 'release-aab/*packaging-evidence.zip'; do
    [ "$(grep -cF -- "$evidence" <<<"$live")" -ge 2 ] ||
        missing="$missing attest-and-verify:$evidence"
done

if [ -n "$missing" ]; then
    contract_fail "publish-production-release does not attest and verify build provenance; missing:${missing}"
fi

# A step that may not run proves nothing: the attest step and the verify step take
# no `if:` and no `continue-on-error`.
step_gates() {
    awk '
        /^      - name:/ { in_step = ($0 ~ /Attest build provenance|Attest the SPDX inventory|Verify build provenance/) ; name = $0; next }
        in_step && /^        (if|continue-on-error):/ { print name ": " $0 }
    ' <<<"$1"
}
gated="$(step_gates "$live")"
[ -z "$gated" ] || contract_fail "an attestation step can be skipped:" "$gated"
seeded="$(sed 's/^      - name: Attest build provenance for the published artifacts$/&\n        if: ${{ false }}/' <<<"$live")"
[ -n "$(step_gates "$seeded")" ] || contract_fail "the step-gate check missed a seeded if: on the attest step"
seeded="$(sed 's/^      - name: Attest the SPDX inventory for the published APK and AAB$/&\n        continue-on-error: true/' <<<"$live")"
[ -n "$(step_gates "$seeded")" ] || contract_fail "the step-gate check missed a seeded continue-on-error on the SBOM step"

contract_pass "publish-production-release attests and verifies build provenance and the SPDX inventory for the published artifacts."
