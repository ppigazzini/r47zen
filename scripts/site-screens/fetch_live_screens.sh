#!/bin/bash
# Put the live site's screens into a directory, for a run whose render failed.
#
# A Pages deploy replaces the whole site, so a run that cannot render the screens
# republishes the ones already live rather than hold back the privacy policy and
# the manual. It fails when the live site lacks any scene scenes.txt lists, as it
# does before the first deploy that carries them; the deploy then waits for a
# render that works.
#
# Usage: fetch_live_screens.sh <output-dir>
# R47_SCREENS_SITE_URL names another site; run_site_screens_contract.sh points it
# at a directory.

set -Eeuo pipefail

output_dir="${1:?usage: $0 <output-dir>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE_URL="${R47_SCREENS_SITE_URL:-https://ppigazzini.github.io/r47zen}"

mapfile -t scenes < <(grep -Ev '^[[:space:]]*(#|$)' "$SCRIPT_DIR/scenes.txt")
rm -rf "$output_dir"
mkdir -p "$output_dir"
for scene in "${scenes[@]}"; do
    curl --fail --silent --show-error --location --retry 3 \
        "$SITE_URL/screens/$scene.svg" --output "$output_dir/$scene.svg"
done
echo "Fetched the ${#scenes[@]} live screens from $SITE_URL." >&2
