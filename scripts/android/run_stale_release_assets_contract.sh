#!/bin/bash
# Contract: a re-cut removes the previous cut's files from its tag, after its own
# upload.
#
# stale_release_assets.sh is fed `gh release view` JSON: a release holding both
# cuts prints the old four and none of the new; one holding only the new prints
# nothing; one missing a just-uploaded file prints nothing and fails, so a partial
# upload deletes nothing. Then android-release.yml: the removal step runs after
# the publish step, only when allow_overwrite is set, and deletes what the script
# printed. Pure host test, no network.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
STALE="$SCRIPT_DIR/stale_release_assets.sh"
RELEASE_WORKFLOW="$WORKFLOW_DIR/android-release.yml"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

old=(r47zen_0.2.0_1d203e24_aaaaaaaa.apk r47zen_0.2.0_1d203e24_aaaaaaaa.aab
    r47zen_0.2.0_1d203e24_aaaaaaaa_apk-packaging-evidence.zip
    r47zen_0.2.0_1d203e24_aaaaaaaa_aab-packaging-evidence.zip)
new=(r47zen_0.2.0_8ab05611_bbbbbbbb.apk r47zen_0.2.0_8ab05611_bbbbbbbb.aab
    r47zen_0.2.0_8ab05611_bbbbbbbb_apk-packaging-evidence.zip
    r47zen_0.2.0_8ab05611_bbbbbbbb_aab-packaging-evidence.zip)
assets_json() {
    python3 -c 'import json, sys; print(json.dumps({"assets": [{"name": n} for n in sys.argv[1:]]}))' "$@"
}

assets_json "${old[@]}" "${new[@]}" >"$work/both.json"
stale="$(bash "$STALE" --assets-json "$work/both.json" "${new[@]}")"
[ "$stale" = "$(printf '%s\n' "${old[@]}")" ] ||
    contract_fail "a re-cut beside the old files should list the old four, got:" "$stale"

assets_json "${new[@]}" >"$work/new.json"
stale="$(bash "$STALE" --assets-json "$work/new.json" "${new[@]}")"
[ -z "$stale" ] || contract_fail "a release holding only the new files listed:" "$stale"

assets_json "${old[@]}" "${new[@]:0:3}" >"$work/partial.json"
if stale="$(bash "$STALE" --assets-json "$work/partial.json" "${new[@]}" 2>/dev/null)"; then
    contract_fail "a release missing an uploaded file passed"
fi
[ -z "$stale" ] || contract_fail "a release missing an uploaded file listed files to delete:" "$stale"

job="$(workflow_job_block "$RELEASE_WORKFLOW" publish-production-release | strip_hash_comments || true)"
[ -n "$job" ] || contract_fail "android-release.yml has no publish-production-release job"
publish_line="$(grep -n 'softprops/action-gh-release@' <<<"$job" | head -n 1 | cut -d: -f1 || true)"
remove_line="$(grep -n 'bash ./scripts/android/stale_release_assets.sh' <<<"$job" | head -n 1 | cut -d: -f1 || true)"
[[ -n "$publish_line" && -n "$remove_line" && "$remove_line" -gt "$publish_line" ]] ||
    contract_fail "publish-production-release does not remove a re-cut's stale files after its upload"
remove_step="$(awk -v from="$remove_line" 'NR >= from - 20 && NR <= from' <<<"$job")"
grep -q "if: inputs.allow_overwrite" <<<"$remove_step" ||
    contract_fail "the stale-file removal is not gated on allow_overwrite"
grep -q 'gh release delete-asset' <<<"$job" ||
    contract_fail "publish-production-release does not delete the files the script printed"

contract_pass "a re-cut removes the previous cut's files from its tag after its own upload, and a partial upload removes nothing."
