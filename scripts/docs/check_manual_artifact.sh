#!/bin/bash
# Refuse a manual artifact that holds anything but the cropped PDF and its VERSION.
#
# The manual job runs upstream's TeX build with -shell-escape, so whatever its
# artifact holds came from a job an upstream commit can steer. The Pages site's
# origin is the one Play Console holds for the privacy policy, so the job that
# stages the site, which runs no upstream code, publishes exactly two regular
# files: C47_R47_calc_Crop.pdf, which starts %PDF-, and VERSION, one line of 64
# hex digits.
#
# Usage: check_manual_artifact.sh <directory>

set -euo pipefail

dir="${1:?usage: $0 <directory>}"
fail() {
    echo "The manual artifact is refused: $1" >&2
    exit 1
}

mapfile -t entries < <(find "$dir" -mindepth 1 -printf '%P\n' | LC_ALL=C sort)
[[ "${#entries[@]}" -eq 2 && "${entries[0]}" == C47_R47_calc_Crop.pdf && "${entries[1]}" == VERSION ]] ||
    fail "it holds ${entries[*]:-nothing}, not exactly C47_R47_calc_Crop.pdf and VERSION"
for name in C47_R47_calc_Crop.pdf VERSION; do
    [[ -f "$dir/$name" && ! -L "$dir/$name" ]] || fail "$name is not a regular file"
done
[[ "$(head -c 5 "$dir/C47_R47_calc_Crop.pdf")" == "%PDF-" ]] || fail "the PDF does not start %PDF-"
[[ "$(wc -l <"$dir/VERSION")" -eq 1 ]] && grep -qxE '[0-9a-f]{64}' "$dir/VERSION" ||
    fail "VERSION is not one line of 64 hex digits"
echo "The manual artifact holds the PDF and its VERSION, and nothing else."
