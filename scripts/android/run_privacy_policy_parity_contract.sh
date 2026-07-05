#!/bin/bash

# Contract: the two privacy-policy copies stay in sync. The canonical
# maintainer/Play-Console copy is android/docs/privacy-policy.md; the copy users
# actually read in-app is android/app/src/main/assets/privacy-policy.html, loaded
# by SettingsActivity. They are hand-maintained with no generator, so they drift
# (the HTML once lost the "Google Play Data Safety" section and its last-updated
# date). This guard fails if their section headings diverge or either loses the
# last-updated date, so a change to one must be mirrored in the other.
#
# Pure-host text check: no SDK, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MD="$PROJECT_ROOT/android/docs/privacy-policy.md"
HTML="$PROJECT_ROOT/android/app/src/main/assets/privacy-policy.html"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

[ -f "$MD" ] || fail "missing $MD"
[ -f "$HTML" ] || fail "missing $HTML"

md_headings="$(sed -n 's/^## \(.*\)$/\1/p' "$MD")"
html_headings="$(grep -oE '<h2>[^<]*</h2>' "$HTML" | sed -E 's#</?h2>##g')"

if [ "$md_headings" != "$html_headings" ]; then
    {
        echo "FAIL: privacy-policy section headings differ between the markdown and the shipped HTML."
        echo "--- markdown (## ) ---"
        printf '%s\n' "$md_headings"
        echo "--- html (<h2>) ---"
        printf '%s\n' "$html_headings"
    } >&2
    exit 1
fi

grep -qi 'Last updated' "$MD" || fail "privacy-policy.md lost its Last updated date."
grep -qi 'Last updated' "$HTML" || fail "privacy-policy.html lost its Last updated date."

echo "OK: the markdown and shipped-HTML privacy policies share section headings and a last-updated date."
