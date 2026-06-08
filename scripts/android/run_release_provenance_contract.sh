#!/bin/bash

# Contract: the production release must publish a SLSA build-provenance
# attestation for the artifacts it ships AND verify it in the same run. The
# publish-production-release job in android-release.yml must therefore declare
# the id-token:write and attestations:write permissions, run
# actions/attest-build-provenance over the published APK and AAB, and verify the
# result with `gh attestation verify`. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKFLOW="$PROJECT_ROOT/.github/workflows/android-release.yml"

# Extract the publish-production-release job block: from its header to the next
# top-level (two-space) job header, or end of file.
block="$(
    awk '
        /^  publish-production-release:/ { inblock = 1; print; next }
        inblock && /^  [a-zA-Z0-9_-]+:/ { inblock = 0 }
        inblock { print }
    ' "$WORKFLOW"
)"

missing=""
grep -q 'id-token: write' <<<"$block" || missing="$missing id-token:write"
grep -q 'attestations: write' <<<"$block" || missing="$missing attestations:write"
grep -q 'uses: actions/attest-build-provenance@' <<<"$block" || missing="$missing actions/attest-build-provenance"
# Require a real `gh attestation verify` command, not just a mention in a
# comment, so the attestation is actually re-verified in the release run.
grep 'gh attestation verify' <<<"$block" | grep -qvE '^[[:space:]]*#' ||
    missing="$missing gh-attestation-verify"

if [ -n "$missing" ]; then
    echo "FAIL: publish-production-release does not attest and verify build provenance; missing:${missing}" >&2
    exit 1
fi

echo "OK: publish-production-release attests and verifies build provenance for the published artifacts."
