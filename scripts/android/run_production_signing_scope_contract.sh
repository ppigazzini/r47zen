#!/bin/bash

# Contract: the production release signing secrets (R47_RELEASE_STORE_FILE_BASE64
# and friends) may be referenced only by the job that signs the published
# artifact -- build-production-release-bundle in android-release.yml. Every other
# job (notably the emulator verification lane) must sign test builds with a
# throwaway key, so the production signing material is materialized in exactly
# one place. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

violations="$(
    awk '
        FNR == 1 { injobs = 0; job = "" }
        /^jobs:/ { injobs = 1 }
        injobs && /^  [a-zA-Z0-9_-]+:/ { job = $1; sub(/:$/, "", job) }
        /secrets\.R47_RELEASE_(STORE_FILE_BASE64|STORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)/ {
            allowed = (FILENAME ~ /android-release\.yml$/ && job == "build-production-release-bundle")
            if (!allowed) {
                short = FILENAME; sub(/.*\//, "", short)
                printf "%s:%d: job=%s: %s\n", short, FNR, job, $0
            }
        }
    ' "$WORKFLOW_DIR"/android-release.yml "$WORKFLOW_DIR"/android-ci.yml 2>/dev/null
)"

if [ -n "$violations" ]; then
    contract_fail \
        "production release signing secrets referenced outside build-production-release-bundle:" \
        "$violations"
fi

contract_pass "production release signing secrets are confined to build-production-release-bundle."
