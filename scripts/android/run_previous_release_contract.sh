#!/bin/bash

# Contract: resolve_previous_release.sh names the release the release lane
# counts from. Fed a synthetic releases API array, it must take the highest
# counter code on an "- Android version <name> (<code>)" line, pair it with
# that release's tag and version name, and skip a draft, a prerelease, a code
# above the cap, a body with no such line, and a line that only quotes one. A
# CRLF body counts, and so does a name from before plain major.minor.patch
# versions. An empty list yields code 0 and no tag or name. Pure host test,
# needs jq.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

RESOLVER="$SCRIPT_DIR/resolve_previous_release.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

cat >"$fixture_dir/releases.json" <<'EOF'
[
  {"tag_name": "r47zen-v0.1.15-draft", "draft": true, "prerelease": false,
   "body": "- Android version 0.1.15 (9)\n"},
  {"tag_name": "r47zen-abc-dev", "draft": false, "prerelease": true,
   "body": "- Android version 0.1.15-12.1 (12)\n"},
  {"tag_name": "r47zen-v0.1.14", "draft": false, "prerelease": false,
   "body": "- Release tag r47zen-v0.1.14\r\n- Android version 0.1.14 (3)\r\n- Sync to commit x\r\n"},
  {"tag_name": "r47zen-v0.1.11-signed.2026101101", "draft": false, "prerelease": false,
   "body": "- Release tag r47zen-v0.1.11-signed.2026101101\r\n- Android version 0.1.11-signed.2026101101 (2)\r\n- Sync to commit x\r\n"},
  {"tag_name": "r47zen-v0.1.10-signed.2026100904", "draft": false, "prerelease": false,
   "body": "- Android version 0.1.10-signed.2026100904 (1)\n"},
  {"tag_name": "r47zen-v0.1.10-signed.2026100903", "draft": false, "prerelease": false,
   "body": "- Android version 0.1.10-signed.2026100903 (2026100903)\n"},
  {"tag_name": "r47zen-v0.1.9-notes", "draft": false, "prerelease": false,
   "body": "Quoted: - Android version 0.1.9 (7)\n"},
  {"tag_name": "r47zen-v0.1.8-nobody", "draft": false, "prerelease": false, "body": null}
]
EOF
printf '[]\n' >"$fixture_dir/empty.json"

check() {
    local desc="$1" file="$2" want="$3" got
    got="$(bash "$RESOLVER" --releases-json "$file" --cap 100000 | paste -sd ' ' -)"
    [ "$got" = "$want" ] ||
        contract_fail "$desc: expected '$want', got '$got'."
}

check "the newest counter release" "$fixture_dir/releases.json" \
    "previous_code=3 previous_tag=r47zen-v0.1.14 previous_name=0.1.14"
check "no release" "$fixture_dir/empty.json" "previous_code=0 previous_tag= previous_name="

# Each skip rule must be what keeps its release out: a cap that admits the
# date-scheme code picks that release instead.
got="$(bash "$RESOLVER" --releases-json "$fixture_dir/releases.json" --cap 3000000000 | paste -sd ' ' -)"
[ "$got" = "previous_code=2026100903 previous_tag=r47zen-v0.1.10-signed.2026100903 previous_name=0.1.10-signed.2026100903" ] ||
    contract_fail "a cap above the date-scheme code did not select it; the cap check is not what skips it: '$got'."

if bash "$RESOLVER" --releases-json "$fixture_dir/releases.json" --cap 0 >/dev/null 2>&1; then
    contract_fail "the resolver accepted a cap of 0."
fi

contract_pass "resolve_previous_release.sh counts from the newest published counter release."
