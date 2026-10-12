#!/usr/bin/env bash
# scripts/docs/check_external_links.sh
#
# Check every external link in the tracked markdown. run_docs_lint.sh holds the
# links inside this repo; an external page moves on its owner's schedule, with
# no commit here, so this runs weekly in the Docs Lint workflow instead of on
# every push.
#
# It fails only on a link that is certainly dead: HTTP 404 or 410 after
# redirects, or a host that does not resolve. Anything else that is not a
# success -- 403 from a site that refuses robots, 429, a 5xx, a timeout -- is
# printed as unjudged and does not fail, because a weekly job that goes red on
# someone else's outage teaches its reader to ignore it.
#
# NOT checked: whether the page still says what the doc cites it for, and
# whether a #fragment still names a heading. Only a reader catches those.
#
# Usage:  bash scripts/docs/check_external_links.sh   # from anywhere; needs network
# Exit:   0 no dead link, 1 a dead link or a failed self-test.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

# Print dead, unjudged, or ok for an HTTP code and a curl exit status.
classify() {
    local code="$1" curl_status="$2"
    case "$curl_status" in
        6) # could not resolve host
            echo dead
            return
            ;;
        0) ;;
        *)
            echo unjudged
            return
            ;;
    esac
    case "$code" in
        2??) echo ok ;;
        404 | 410) echo dead ;;
        *) echo unjudged ;;
    esac
}

# Prove the classifier before trusting it.
for case_line in "200 0 ok" "404 0 dead" "410 0 dead" "403 0 unjudged" \
    "429 0 unjudged" "503 0 unjudged" "000 6 dead" "000 28 unjudged"; do
    read -r code status want <<<"$case_line"
    got="$(classify "$code" "$status")"
    if [[ "$got" != "$want" ]]; then
        echo "check_external_links: self-test failed: code $code, curl $status gave $got, want $want" >&2
        exit 1
    fi
done

mapfile -t urls < <(git ls-files -z '*.md' | xargs -0 grep -ohE 'https?://[^] )>`"]+' |
    sed -E 's/[.,;:]+$//' | sort -u)
if [[ ${#urls[@]} -eq 0 ]]; then
    echo "check_external_links: no external link found (is this a git checkout?)" >&2
    exit 1
fi

dead=0
unjudged=0
for url in "${urls[@]}"; do
    code="$(curl -sS -o /dev/null -L --max-time 30 --retry 2 \
        -A 'Mozilla/5.0 (compatible; r47zen-docs-link-check)' \
        -w '%{http_code}' "$url" 2>/dev/null)"
    curl_status=$?
    case "$(classify "$code" "$curl_status")" in
        dead)
            echo "DEAD      $code $url"
            dead=$((dead + 1))
            ;;
        unjudged)
            echo "UNJUDGED  $code (curl $curl_status) $url"
            unjudged=$((unjudged + 1))
            ;;
    esac
done

echo "external links: ${#urls[@]} checked, $dead dead, $unjudged unjudged"
[[ "$dead" -eq 0 ]]
