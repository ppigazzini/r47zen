#!/bin/bash
# Contract: packaging evidence refuses an artifact that lost a compliance asset.
#
# check_compliance_assets.sh is run on unpacked trees: one carrying COPYING,
# LICENSE.txt, SOURCE, and THIRD-PARTY.spdx.json passes and lists them; one that
# lacks any one of them, or holds it empty, fails, with no evidence written in
# its place. Then collect_packaging_evidence.sh: it calls the checker for the APK
# and the AAB, and writes no compliance file of its own. Pure host
# test, no SDK.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
CHECK="$SCRIPT_DIR/check_compliance_assets.sh"
COLLECT="$SCRIPT_DIR/collect_packaging_evidence.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

make_tree() {
    local root="$1"
    mkdir -p "$root/assets/repo-notices"
    for name in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json; do
        printf '%s\n' "$name" >"$root/assets/$name"
    done
    printf 'notice\n' >"$root/assets/repo-notices/index.json"
}

make_tree "$work/complete"
bash "$CHECK" "$work/complete" assets "$work/out-complete" ||
    contract_fail "check_compliance_assets.sh refused a complete artifact"
listed="$(cat "$work/out-complete/PACKAGED-COMPLIANCE-ASSETS.txt")"
for name in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json repo-notices/index.json; do
    grep -qx "compliance-assets/$name" <<<"$listed" ||
        contract_fail "the evidence does not list compliance-assets/$name"
done

for name in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json; do
    for how in missing empty; do
        tree="$work/$how-$name"
        make_tree "$tree"
        if [[ "$how" == missing ]]; then rm "$tree/assets/$name"; else : >"$tree/assets/$name"; fi
        if bash "$CHECK" "$tree" assets "$work/out-$how-$name" 2>/dev/null; then
            contract_fail "check_compliance_assets.sh passed an artifact with $name $how"
        fi
        [ ! -e "$work/out-$how-$name/compliance-assets/$name" ] ||
            contract_fail "check_compliance_assets.sh wrote $name for an artifact $how it"
    done
done

live="$(strip_hash_comments <"$COLLECT")"
for root in '"assets"' '"base/assets"'; do
    grep -q "check_compliance_assets.sh\" \"\$unpack_dir\" $root" <<<"$live" ||
        contract_fail "collect_packaging_evidence.sh does not check the compliance assets under $root"
done
if grep -qE 'write_source_manifest|write_xlsxio_license|populate_compliance_assets' <<<"$live"; then
    contract_fail "collect_packaging_evidence.sh still writes a compliance file of its own"
fi

contract_pass "packaging evidence refuses an artifact that lost COPYING, LICENSE.txt, SOURCE, or THIRD-PARTY.spdx.json."
