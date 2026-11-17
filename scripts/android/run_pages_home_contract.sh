#!/bin/bash
#
# Contract: the Pages home page publishes from android/site/index.html with the
# launcher icon's drawing source as its logo, every link it holds into the site
# names a file the site publishes, and it loads nothing from another origin and runs
# no script. Runs scripts/docs/stage_pages_home.sh on a site seeded with the
# privacy policy and a stand-in manual, then on seeded pages.
#
# It fails when:
#
# - the real page does not stage beside the policy and the manual, or the staged
#   page or icon differs from its source;
# - the stage passes a page with a link into the site that names no staged file, a
#   tag split across lines included, or one that climbs out of the site or starts
#   at the host's root;
# - it passes a resource loaded from another origin, an http, protocol-relative, or
#   javascript link, an unquoted link, a script, an event handler, or a page that
#   lost its Content-Security-Policy, or an icon that holds a script;
# - it refuses a dead link inside an HTML comment;
# - the build job stages the home page before the manual or the policy is in the
#   site, or the push filter misses the page, the icon, or the stage script;
# - the page says the app collects no personal data, or requests no Internet
#   permission, and the privacy policy stops saying it.
#
# Pure host check: no SDK, no browser, no network. It cannot tell whether a
# browser renders the page as intended, or whether an external link still serves
# a page.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

STAGE="$PROJECT_ROOT/scripts/docs/stage_pages_home.sh"
PAGE_REL="android/site/index.html"
ICON_REL="android/app/icon-source/r47zen.svg"
POLICY_REL="android/app/src/main/assets/privacy-policy.html"
PAGES="$WORKFLOW_DIR/privacy-policy-pages.yml"

for file in "$STAGE" "$PROJECT_ROOT/$PAGE_REL" "$PROJECT_ROOT/$ICON_REL" "$PROJECT_ROOT/$POLICY_REL" "$PAGES"; do
    [ -f "$file" ] || contract_fail "missing required file: ${file#"$PROJECT_ROOT/"}"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Make a site holding what the build job stages before the home page.
seed_site() {
    mkdir -p "$1/manual"
    cp "$PROJECT_ROOT/$POLICY_REL" "$1/privacy-policy.html"
    printf '%%PDF-1.7\n' >"$1/manual/C47_R47_calc_Crop.pdf"
    printf '%064d\n' 0 >"$1/manual/VERSION"
}

seed_site "$work/real"
bash "$STAGE" "$work/real" >/dev/null 2>"$work/stderr" ||
    contract_fail "the real home page did not stage beside the policy and the manual." "$(cat "$work/stderr")"
cmp -s "$work/real/index.html" "$PROJECT_ROOT/$PAGE_REL" ||
    contract_fail "the staged index.html is not $PAGE_REL."
cmp -s "$work/real/r47zen.svg" "$PROJECT_ROOT/$ICON_REL" ||
    contract_fail "the staged r47zen.svg is not $ICON_REL."

# Stage a copy of the tree whose page or icon a perl substitution changed. Prints
# nothing; returns the stage's status.
stage_mutant() {
    local name="$1" file="$2" substitution="$3"
    local root="$work/root-$name" site="$work/site-$name"
    mkdir -p "$root/android/site" "$root/android/app/icon-source"
    cp "$PROJECT_ROOT/$PAGE_REL" "$root/$PAGE_REL"
    cp "$PROJECT_ROOT/$ICON_REL" "$root/$ICON_REL"
    perl -0pi -e "$substitution" "$root/$file"
    ! cmp -s "$root/$file" "$PROJECT_ROOT/$file" ||
        contract_fail "the $name mutation changed nothing in $file."
    seed_site "$site"
    R47_PAGES_ROOT="$root" bash "$STAGE" "$site" >/dev/null 2>"$work/stderr-$name"
}

# Each body element goes in before </main>, each head element before </head>.
refused_body=(
    'dead|<a href="missing.html">x</a>'
    'split-tag|<a\n  href="missing.html">x</a>'
    'climb|<a href="manual/../../x.html">x</a>'
    'host-root|<a href="/r47zen/privacy-policy.html">x</a>'
    'remote-image|<img src="https://example.com/a.png" alt="">'
    'protocol-relative|<img src="//example.com/a.png" alt="">'
    'http-link|<a href="http://example.com/">x</a>'
    'javascript-link|<a href="javascript:void(0)">x</a>'
    'unquoted|<a href=privacy-policy.html>x</a>'
    'script|<script>document.title = 1;</script>'
    'handler|<p onclick="x()">x</p>'
)
for entry in "${refused_body[@]}"; do
    name="${entry%%|*}"
    element="${entry#*|}"
    if stage_mutant "$name" "$PAGE_REL" "s#</main>#$element\n</main>#"; then
        contract_fail "stage_pages_home.sh passed a page with $name: $element"
    fi
done

if stage_mutant stylesheet "$PAGE_REL" 's#</head>#<link rel="stylesheet" href="https://example.com/a.css">\n</head>#'; then
    contract_fail "stage_pages_home.sh passed a page that loads a stylesheet from another origin."
fi
if stage_mutant no-csp "$PAGE_REL" 's#<meta http-equiv="Content-Security-Policy"[^>]*>\n##'; then
    contract_fail "stage_pages_home.sh passed a page with no Content-Security-Policy."
fi
if stage_mutant icon-script "$ICON_REL" 's#</svg>#<script>alert(1)</script>\n</svg>#'; then
    contract_fail "stage_pages_home.sh passed an icon that holds a script."
fi
stage_mutant commented "$PAGE_REL" 's#</main>#<!-- <a href="missing.html">x</a> -->\n</main>#' ||
    contract_fail "stage_pages_home.sh refused a dead link inside an HTML comment." "$(cat "$work/stderr-commented")"

pages="$(strip_hash_comments <"$PAGES")"
build_job="$(awk '
    /^  build:[[:space:]]*$/ { inside = 1; next }
    inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { inside = 0 }
    inside { print }
' <<<"$pages")"
[ -n "$build_job" ] || contract_fail "privacy-policy-pages.yml has no build job."
line_of() { grep -nF -- "$1" <<<"$build_job" | head -n 1 | cut -d: -f1 || true; }
stage_line="$(line_of 'bash ./scripts/docs/stage_pages_home.sh _site')"
manual_line="$(line_of 'check_manual_artifact.sh _site/manual')"
policy_line="$(line_of "cp $POLICY_REL _site/privacy-policy.html")"
[ -n "$stage_line" ] ||
    contract_fail "the build job does not run stage_pages_home.sh on _site."
[ -n "$manual_line" ] && [ -n "$policy_line" ] &&
    [ "$stage_line" -gt "$manual_line" ] && [ "$stage_line" -gt "$policy_line" ] ||
    contract_fail "the build job stages the home page before the manual and the policy are in the site."

push_paths="$(awk '
    /^[[:space:]]+paths:[[:space:]]*$/ { inpaths = 1; next }
    inpaths && /^[[:space:]]+- / { print; next }
    inpaths { inpaths = 0 }
' <<<"$pages")"
for path in "$PAGE_REL" "$ICON_REL" scripts/docs/stage_pages_home.sh; do
    grep -Eq -- "- $path\$" <<<"$push_paths" ||
        contract_fail "privacy-policy-pages.yml's push paths do not name $path; an edit would not republish." \
            "push paths read:" "$push_paths"
done

# Compare prose with its line breaks folded, as a reader sees it.
fold_space() { tr -s '[:space:]' ' ' <"$1"; }
page_text="$(fold_space "$PROJECT_ROOT/$PAGE_REL")"
policy_text="$(fold_space "$PROJECT_ROOT/$POLICY_REL")"
for claim in "collects no personal data" "does not request the Android Internet permission"; do
    if [[ "$page_text" == *"$claim"* && "$policy_text" != *"$claim"* ]]; then
        contract_fail "the home page says the app $claim, and the privacy policy no longer does."
    fi
done

contract_pass "the home page stages from $PAGE_REL with its icon, links only to files the site holds, and loads nothing from another origin."
