#!/bin/bash
# Refuse a screens artifact that holds anything but the SVGs site_screens.c writes.
#
# The screens job builds and runs the upstream core, so whatever its artifact holds
# came from a job an upstream commit can steer. The Pages site's origin is the one
# Play Console holds for the privacy policy, so the job that stages the site, which
# runs no upstream code, publishes exactly one regular file per scene that
# scenes.txt lists, <scene>.svg, each one line in the shape write_svg emits: a 400
# x 240 svg holding one background rect and one path of rectangles, with no other
# element, attribute, or text. A file over MAX_BYTES is refused too: a full screen
# of single pixels stays under it.
#
# Usage: check_screens_artifact.sh <directory>

set -euo pipefail

dir="${1:?usage: $0 <directory>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAX_BYTES=2000000

fail() {
    echo "The screens artifact is refused: $1" >&2
    exit 1
}

mapfile -t scenes < <(grep -Ev '^[[:space:]]*(#|$)' "$SCRIPT_DIR/scenes.txt")
[ "${#scenes[@]}" -gt 0 ] || fail "scenes.txt names no scene"
expected="$(printf '%s.svg\n' "${scenes[@]}" | LC_ALL=C sort)"
actual="$(find "$dir" -mindepth 1 -printf '%P\n' | LC_ALL=C sort)"
[ "$actual" = "$expected" ] ||
    fail "it holds $(tr '\n' ' ' <<<"$actual"), not exactly $(tr '\n' ' ' <<<"$expected")"

svg_shape='^<svg xmlns="http://www\.w3\.org/2000/svg" viewBox="0 0 400 240" width="400" height="240" shape-rendering="crispEdges"><rect width="400" height="240" fill="#[0-9a-f]{6}"/><path fill="#[0-9a-f]{6}" d="(M[0-9]{1,3} [0-9]{1,3}h[0-9]{1,3}v[0-9]{1,3}h-[0-9]{1,3}z)*"/></svg>$'
for scene in "${scenes[@]}"; do
    file="$dir/$scene.svg"
    [[ -f "$file" && ! -L "$file" ]] || fail "$scene.svg is not a regular file"
    [ "$(stat -c %s "$file")" -le "$MAX_BYTES" ] || fail "$scene.svg is over $MAX_BYTES bytes"
    [ "$(wc -l <"$file")" -eq 1 ] || fail "$scene.svg is not one line"
    grep -Eq "$svg_shape" "$file" || fail "$scene.svg is not in the shape site_screens.c writes"
done
echo "The screens artifact holds ${#scenes[@]} SVGs in the shape site_screens.c writes, and nothing else."
