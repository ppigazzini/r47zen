#!/bin/bash
#
# Put the cropped manual and its VERSION into a directory for the Pages site, from
# the upstream tree hydrated at the repo root, or report that nothing changed.
#
# Usage: prepare_manual_site.sh <output-dir> <event-name>
#
# Stdout carries one line for GITHUB_OUTPUT: deploy=true when the directory is ready
# to publish, deploy=false when a scheduled run found the live PDF current. A run for
# any other event must republish, because a Pages deploy replaces the whole site, so
# it fetches the live PDF rather than build the same one again.
#
# The build runs upstream's own command in a container with no network and no
# credentials: .latexmkrc passes -shell-escape, so the manual's sources can run
# commands. A failure exits non-zero, so the lane deploys nothing and the last good
# PDF stays live. scripts/android/run_manual_site_contract.sh runs every branch.

set -Eeuo pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <output-dir> <event-name>" >&2
    exit 2
fi
output_dir="$1"
event_name="$2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${R47_MANUAL_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
SITE_URL="${R47_MANUAL_SITE_URL:-https://ppigazzini.github.io/r47zen}"

# Upstream's docs-manual job builds on texlive/texlive:latest. Pin what this lane
# ran, and take the newest by hand: the digest is part of the fingerprint below, so
# a bump rebuilds the PDF.
TEX_IMAGE="texlive/texlive@sha256:07e8062637249c4f780ca59f42cc2ed592bc2dc1db4320769406aee3f98aa70e"

PDF_NAME="C47_R47_calc_Crop.pdf"
BUILT_PDF="docs/manual/$PDF_NAME"

# What the build reads, from the docs-manual gate in upstream's .gitlab-ci.yml.
MANUAL_INPUTS=(docs/manual/tex docs/manual/Makefile docs/styles res/fonts docs/tools)

# Well under upstream's published crop of about 1.4 MB, to catch a truncated build,
# not to judge the book.
MIN_PDF_BYTES=500000

log() {
    echo "$*" >&2
}

manual_fingerprint() {
    {
        printf '%s\n' "$TEX_IMAGE"
        find "${MANUAL_INPUTS[@]}" -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
    } | sha256sum | cut -d' ' -f1
}

pdf_looks_whole() {
    local pdf="$1"
    [ -f "$pdf" ] &&
        [ "$(head -c 5 "$pdf")" = "%PDF-" ] &&
        [ "$(stat -c %s "$pdf")" -ge "$MIN_PDF_BYTES" ]
}

fetch_live() {
    curl --fail --silent --show-error --location --retry 3 "$SITE_URL/manual/$1" --output "$2"
}

build_pdf() {
    docker pull --quiet "$TEX_IMAGE" >&2
    docker run --rm --network none \
        --user "$(id -u):$(id -g)" --env HOME=/tmp \
        --volume "$ROOT:/work" --workdir /work \
        "$TEX_IMAGE" make -C docs/manual pdf VARIANT=crop >&2
}

cd "$ROOT"
for input in "${MANUAL_INPUTS[@]}"; do
    if [ ! -e "$input" ]; then
        log "ERROR: $input is missing: upstream moved a manual input, so update MANUAL_INPUTS."
        exit 1
    fi
done

fingerprint="$(manual_fingerprint)"
mkdir -p "$output_dir"
live_version="$output_dir/live-VERSION"
live_fingerprint=""
if fetch_live VERSION "$live_version" 2>/dev/null; then
    live_fingerprint="$(head -n 1 "$live_version")"
fi
rm -f "$live_version"

if [ "$live_fingerprint" = "$fingerprint" ]; then
    if [ "$event_name" = "schedule" ]; then
        log "The live manual is current ($fingerprint)."
        echo "deploy=false"
        exit 0
    fi
    if fetch_live "$PDF_NAME" "$output_dir/$PDF_NAME" && pdf_looks_whole "$output_dir/$PDF_NAME"; then
        log "Republishing the live manual ($fingerprint)."
        printf '%s\n' "$fingerprint" >"$output_dir/VERSION"
        echo "deploy=true"
        exit 0
    fi
    log "The live PDF is unusable, so building it again."
fi

log "Building the manual ($fingerprint)."
build_pdf
if ! pdf_looks_whole "$BUILT_PDF"; then
    log "ERROR: the build left no whole PDF at $BUILT_PDF."
    exit 1
fi
cp "$BUILT_PDF" "$output_dir/$PDF_NAME"
printf '%s\n' "$fingerprint" >"$output_dir/VERSION"
echo "deploy=true"
