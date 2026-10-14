#!/bin/bash

# Contract: the privacy policy is one file. The app shows
# android/app/src/main/assets/privacy-policy.html from Settings, and
# .github/workflows/privacy-policy-pages.yml publishes that same file to the
# GitHub Pages URL in Play Console's privacy-policy field, so the in-app page
# and the Play page cannot drift. This guard fails when:
#
# - the asset is missing, or loses its "Privacy Policy" title or heading
#   (Google Play requires the page to be labelled as one) or its Last updated
#   date;
# - SettingsActivity stops opening that asset;
# - the workflow stops copying it into the site, or its push filter stops
#   naming it, so an edit would ship in the app and never reach the web page;
# - a second tracked privacy-policy file appears beside it.
#
# Pure-host text check: no SDK, no build, no network.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

ASSET_REL="android/app/src/main/assets/privacy-policy.html"
ASSET="$PROJECT_ROOT/$ASSET_REL"
SETTINGS="$PROJECT_ROOT/android/app/src/main/java/io/github/ppigazzini/r47zen/SettingsActivity.kt"
PAGES="$WORKFLOW_DIR/privacy-policy-pages.yml"

for file in "$ASSET" "$SETTINGS" "$PAGES"; do
    [ -f "$file" ] || contract_fail "missing required file: ${file#"$PROJECT_ROOT/"}"
done

asset="$(cat "$ASSET")"
grep -Eq '<title>[^<]*Privacy Policy[^<]*</title>' <<<"$asset" ||
    contract_fail "$ASSET_REL has no <title> naming it a Privacy Policy."
grep -Eq '<h1>Privacy Policy</h1>' <<<"$asset" ||
    contract_fail "$ASSET_REL has no <h1>Privacy Policy</h1> heading."
grep -q 'Last updated' <<<"$asset" ||
    contract_fail "$ASSET_REL lost its Last updated date."

settings="$(strip_c_comments "$SETTINGS")"
grep -q '"privacy-policy.html"' <<<"$settings" ||
    contract_fail "SettingsActivity.kt no longer opens the privacy-policy.html asset."

pages="$(strip_hash_comments <"$PAGES")"
grep -Eq "cp $ASSET_REL _site/privacy-policy\\.html" <<<"$pages" ||
    contract_fail "privacy-policy-pages.yml no longer copies $ASSET_REL into the site."
# The on.push.paths list: the lines after "paths:" that are list items.
push_paths="$(awk '
    /^[[:space:]]+paths:[[:space:]]*$/ { inpaths = 1; next }
    inpaths && /^[[:space:]]+- / { print; next }
    inpaths { inpaths = 0 }
' <<<"$pages")"
grep -Eq -- "- $ASSET_REL\$" <<<"$push_paths" ||
    contract_fail "privacy-policy-pages.yml's push paths no longer name $ASSET_REL; an edit would not republish." \
        "push paths read:" "$push_paths"

tracked="$(git -C "$PROJECT_ROOT" ls-files '*privacy-policy.*')"
[ "$tracked" = "$ASSET_REL" ] ||
    contract_fail "the privacy policy must be one tracked file, $ASSET_REL; found:" "$tracked"

contract_pass "the app and the Pages workflow use one privacy-policy file, $ASSET_REL."
