#!/bin/bash
# Contract: a release refuses a commit pair android-ci has not passed.
#
# require_green_ci_pair.sh is fed `gh release view` JSON from fixtures: a -dev
# release with an APK passes; one with only other assets, an empty answer, and a
# missing tag fail; --warn-only passes each of those with a warning. Then the
# release workflow: the job that runs it is needed by the build job, passes
# --warn-only only in a dry run, and reads the release through gh into the file.
# Pure host test, no network.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
CHECK="$SCRIPT_DIR/require_green_ci_pair.sh"
RELEASE_WORKFLOW="$WORKFLOW_DIR/android-release.yml"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

upstream=1d203e24b3db11dc8d02757f61f74cce969022e8
android=a166235d34ce419a143572582188fee6ccfafefc
printf '%s' '{"assets":[{"name":"r47zen_0.3.0-1.1_1d203e24_a166235d.apk"},{"name":"x.zip"}]}' >"$work/green.json"
printf '%s' '{"assets":[{"name":"r47zen-1d203e24-a166235d-apk-packaging-evidence.zip"}]}' >"$work/no-apk.json"
: >"$work/empty.json"

expect() {
    local want="$1" name="$2"
    shift 2
    local status=0
    bash "$CHECK" --upstream-commit "$upstream" --android-commit "$android" "$@" >"$work/out" 2>&1 || status=$?
    if [[ "$want" == pass && "$status" -ne 0 ]] || [[ "$want" == fail && "$status" -eq 0 ]]; then
        contract_fail "require_green_ci_pair.sh: $name should $want, exit $status" "$(cat "$work/out")"
    fi
}

expect pass "a -dev release with an APK" --release-json "$work/green.json"
expect fail "a -dev release with no APK" --release-json "$work/no-apk.json"
expect fail "an empty answer" --release-json "$work/empty.json"
expect fail "a missing tag" --release-json "$work/missing.json"
expect pass "a missing tag with --warn-only" --release-json "$work/missing.json" --warn-only
grep -q '^::warning::' "$work/out" || contract_fail "--warn-only did not report the missing pair"
expect fail "a short commit" --release-json "$work/green.json" --upstream-commit 1d203e24

job="$(workflow_job_block "$RELEASE_WORKFLOW" require-green-ci | strip_hash_comments || true)"
[ -n "$job" ] || contract_fail "android-release.yml has no require-green-ci job"
grep -q 'require_green_ci_pair.sh' <<<"$job" || contract_fail "require-green-ci does not run require_green_ci_pair.sh"
grep -q 'gh release view' <<<"$job" || contract_fail "require-green-ci does not read the -dev release"
if ! grep -Eq 'R47_DRY_RUN" == "true".*--warn-only|--warn-only.*R47_DRY_RUN' <<<"$(tr '\n' ' ' <<<"$job")"; then
    contract_fail "require-green-ci does not tie --warn-only to a dry run"
fi
build="$(workflow_job_block "$RELEASE_WORKFLOW" build-production-release-bundle | strip_hash_comments || true)"
grep -Eq '^      - require-green-ci$' <<<"$build" ||
    contract_fail "build-production-release-bundle does not need require-green-ci"

contract_pass "a release needs android-ci's -dev release with an APK for its exact commit pair."
