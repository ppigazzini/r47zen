#!/bin/bash
#
# Stage the Pages home page into the site directory: android/site/index.html as
# index.html, and the launcher icon's drawing source,
# android/app/icon-source/r47zen.svg, as r47zen.svg, the page's logo and favicon.
#
# Run it after the manual and the privacy policy are staged: it refuses a page whose
# link into the site names a file the site does not hold, so a moved manual or policy
# fails the deploy instead of publishing a dead link.
#
# The site's origin serves the privacy-policy URL Play Console names, so the page
# loads nothing from another origin and runs no script. It carries the
# Content-Security-Policy in CSP below, which makes the browser refuse both, and
# this script refuses a page that drops it, holds a <script> or an event handler,
# loads a resource from outside the site, or links with any scheme but https. An
# <a> may link to an https page elsewhere. The icon is refused if it holds a
# script or an event handler, since a browser runs one when the SVG is opened on
# its own, and the page's policy does not cover it.
#
# Usage: stage_pages_home.sh <site-dir>
# R47_PAGES_ROOT names another tree to stage from; run_pages_home_contract.sh runs
# it on seeded pages.

set -Eeuo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <site-dir>" >&2
    exit 2
fi
site="$1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${R47_PAGES_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

CSP="default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

fail() {
    echo "The home page is refused: $1" >&2
    exit 1
}

[ -d "$site" ] || fail "the site directory $site does not exist."
cp "$ROOT/android/site/index.html" "$site/index.html"
cp "$ROOT/android/app/icon-source/r47zen.svg" "$site/r47zen.svg"

# Print the file with <!-- ... --> comments removed, so a commented-out line does
# not count.
uncommented() { perl -0pe 's/<!--.*?-->//gs' "$1"; }

for file in index.html r47zen.svg; do
    text="$(uncommented "$site/$file")"
    if grep -Eiq '<script|[[:space:]]on[a-z]+[[:space:]]*=' <<<"$text"; then
        fail "$file holds a script or an event handler."
    fi
done

page="$(uncommented "$site/index.html")"
grep -Fq "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">" <<<"$page" ||
    fail "index.html lost its Content-Security-Policy, $CSP."

# One "tag attribute value" line per href and src, the value as written; the page is
# read whole, so a tag may span lines.
mapfile -t refs < <(perl -0777 -ne '
    while (/<([A-Za-z][A-Za-z0-9]*)\b([^>]*)>/g) {
        my ($tag, $attrs) = (lc $1, $2);
        while ($attrs =~ /(?<![\w-])(href|src)\s*=\s*("[^"]*"|\S+)/gi) {
            print "$tag ", lc $1, " $2\n";
        }
    }' <<<"$page")

for ref in "${refs[@]}"; do
    read -r tag attr value <<<"$ref"
    [[ "$value" == \"*\" ]] || fail "<$tag $attr=$value> is not double-quoted."
    value="${value:1:${#value}-2}"
    case "$value" in
        https://*)
            [ "$tag" = a ] || fail "<$tag $attr=\"$value\"> loads from another origin."
            ;;
        '#'*) ;;
        '' | /* | *:*)
            fail "<$tag $attr=\"$value\"> is not an https link or a path inside the site."
            ;;
        *)
            path="${value%%[#?]*}"
            [[ "/$path/" != */../* ]] || fail "<$tag $attr=\"$value\"> climbs out of the site."
            [ -f "$site/$path" ] || fail "<$tag $attr=\"$value\"> names $path, which the site does not hold."
            ;;
    esac
done

echo "Staged the home page and its icon; every link into the site resolves."
