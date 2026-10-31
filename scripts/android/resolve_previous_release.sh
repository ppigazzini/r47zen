#!/bin/bash

# Name the previous production release: the highest version code a published
# production release carries, at or below the counter cap, that release's tag,
# and its version name. The release lane reads the code to require exactly
# previous + 1, the tag to compare signers, and the name for the version check
# in check_release_version.sh.
#
# Input is the JSON array the GitHub releases API returns. A production release
# is one that is neither a draft nor a prerelease; its code is the number on the
# body line the release workflow writes, "- Android version <name> (<code>)".
# Codes above the cap belong to the retired date scheme (2026100903) and are
# skipped, so a counter release is never compared with one. A body edited in
# the web UI may end its lines in CRLF.
#
# Prints three lines, previous_code=<n>, previous_tag=<tag> and
# previous_name=<name>; 0 and an empty tag and name when no release qualifies.

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/android/resolve_previous_release.sh --releases-json <file> --cap <max code>
EOF
}

releases_json=""
cap=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --releases-json)
            releases_json="$2"
            shift 2
            ;;
        --cap)
            cap="$2"
            shift 2
            ;;
        --help | -h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

[[ -f "$releases_json" ]] || {
    usage >&2
    echo "--releases-json must name a file." >&2
    exit 1
}
[[ "$cap" =~ ^[1-9][0-9]*$ ]] || {
    usage >&2
    echo "--cap must be a positive integer." >&2
    exit 1
}

# One "<tag> <body line>" row per body line that names the Android version.
rows="$(jq -r '
    .[]
    | select((.draft | not) and (.prerelease | not))
    | .tag_name as $tag
    | (.body // "") | split("\n")[]
    | select(startswith("- Android version "))
    | "\($tag) \(.)"
' "$releases_json")"

sed -n 's/^\([^ ]*\) - Android version \([^ ]*\) (\([0-9]*\))\r\{0,1\}$/\3 \1 \2/p' <<<"$rows" |
    awk -v cap="$cap" '
        $1 <= cap && $1 > max { max = $1; tag = $2; name = $3 }
        END { printf "previous_code=%d\nprevious_tag=%s\nprevious_name=%s\n", max, tag, name }
    '
