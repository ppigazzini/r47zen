#!/bin/bash
# Contract: android-release.yml's dry run exercises the release lane and can
# publish nothing.
#
# A change to the release lane otherwise first runs at the release that needs it,
# so the workflow takes a dry_run input, allowed on any branch, that
# builds, signs with a key the run generates, and verifies, and stops there. The
# real release keeps its gates: main only, the production-release environment,
# and the tracked signer pin. What has to hold:
#
#   * dry_run is a boolean input that defaults to false;
#   * publish-production-release, upload-to-play, and sign-production-release
#     run only on main and only when dry_run is false;
#   * sign-dry-run runs only in a dry run, binds no environment, and names no
#     secret and no Play variable, so it can neither reach the release key nor
#     the Play identity;
#   * a dry run refuses any play_track but none before anything builds;
#   * verify-signed-artifacts holds a real release to the tracked signer pin.
#
# The checker first proves it can fail: each seeded copy below must be flagged,
# and the tracked workflow must pass. Pure host text check, no SDK.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
RELEASE_WORKFLOW="$WORKFLOW_DIR/android-release.yml"

[ -f "$RELEASE_WORKFLOW" ] || contract_fail "missing ${RELEASE_WORKFLOW#"$PROJECT_ROOT/"}"

job_if() {
    workflow_job_block "$1" "$2" | strip_hash_comments | sed -n 's/^    if: //p' | head -n 1
}

# Print one line per violation in FILE; print nothing when it holds.
check_release_workflow() {
    local file="$1" job condition block
    local live
    live="$(strip_hash_comments <"$file")"

    if ! awk '/^      dry_run:$/ { found = 1; next }
              found && /^      [A-Za-z_]+:$/ { exit }
              found && /type: boolean/ { boolean = 1 }
              found && /default: false/ { off = 1 }
              END { exit !(boolean && off) }' <<<"$live"; then
        echo "dry_run is not a boolean input that defaults to false"
    fi

    for job in sign-production-release publish-production-release upload-to-play; do
        condition="$(job_if "$file" "$job")"
        if [[ "$condition" != *"github.ref == 'refs/heads/main'"* ||
            "$condition" != *"!inputs.dry_run"* ]]; then
            echo "$job does not run on main only and never in a dry run: if: ${condition:-<none>}"
        fi
    done

    block="$(workflow_job_block "$file" sign-dry-run | strip_hash_comments)"
    if [ -z "$block" ]; then
        echo "there is no sign-dry-run job"
    else
        condition="$(job_if "$file" sign-dry-run)"
        if [[ "$condition" != "inputs.dry_run" ]]; then
            echo "sign-dry-run does not run in a dry run only: if: ${condition:-<none>}"
        fi
        if grep -Eq '^    environment:' <<<"$block"; then
            echo "sign-dry-run binds an environment"
        fi
        if grep -Eq 'secrets\.|secrets\[|toJSON\(secrets\)|vars\.R47_PLAY' <<<"$block"; then
            echo "sign-dry-run names a secret or the Play identity"
        fi
    fi

    block="$(workflow_job_block "$file" resolve-release-inputs | strip_hash_comments)"
    if ! grep -q 'R47_DRY_RUN: \${{ inputs.dry_run }}' <<<"$block" ||
        ! grep -q 'a dry run takes play_track none' <<<"$block"; then
        echo "resolve-release-inputs does not refuse a dry run with a Play track"
    fi

    block="$(workflow_job_block "$file" verify-signed-artifacts | strip_hash_comments)"
    if ! grep -q 'R47_DEFAULT_RELEASE_SIGNER_CERT_SHA256' <<<"$block"; then
        echo "verify-signed-artifacts does not hold a release to the tracked signer pin"
    fi
}

seed_dir="$(mktemp -d)"
trap 'rm -rf "$seed_dir"' EXIT

seed() {
    local name="$1" expression="$2"
    sed -E "$expression" "$RELEASE_WORKFLOW" >"$seed_dir/$name.yml"
    if cmp -s "$RELEASE_WORKFLOW" "$seed_dir/$name.yml"; then
        contract_fail "seed '$name' changed nothing; the workflow no longer has the line it edits"
    fi
    if [ -z "$(check_release_workflow "$seed_dir/$name.yml")" ]; then
        contract_fail "the checker missed the seeded '$name'"
    fi
}

seed publish-in-dry-run "/^  publish-production-release:/,/^    if:/ s/ && !inputs\\.dry_run//"
seed play-in-dry-run "/^  upload-to-play:/,/^    if:/ s/ && !inputs\\.dry_run//"
seed sign-real-in-dry-run "/^  sign-production-release:/,/^    if:/ s/ && !inputs\\.dry_run//"
seed dry-run-environment "/^  sign-dry-run:/,/^    if:/ s/^(    if: .*)$/\\1\\n    environment: production-release/"
seed dry-run-secret "/^  sign-dry-run:/,/^  [a-z]/ s/R47_SIGNING_STORE_PASSWORD: throwaway/R47_SIGNING_STORE_PASSWORD: \${{ secrets.R47_RELEASE_STORE_PASSWORD }}/"
seed dry-run-default-on "/^      dry_run:$/,/default:/ s/default: false/default: true/"
seed no-play-refusal "s/a dry run takes play_track none/a dry run proceeds/"

violations="$(check_release_workflow "$RELEASE_WORKFLOW")"
if [ -n "$violations" ]; then
    mapfile -t lines <<<"$violations"
    contract_fail "android-release.yml's dry run could publish or reach a credential:" "${lines[@]}"
fi
contract_pass "the release dry run publishes nothing and holds no credential (7 seeds flagged)."
