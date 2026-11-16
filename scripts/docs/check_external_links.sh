#!/usr/bin/env bash
# scripts/docs/check_external_links.sh
#
# Check every external link in the tracked markdown. run_docs_lint.sh holds the
# links inside this repo; an external page moves on its owner's schedule, with
# no commit here, so this runs weekly in the Docs Lint workflow instead of on
# every push.
#
# It fails only on a link that is certainly dead: HTTP 404 or 410 after
# redirects, or a host that does not resolve, on a first try and again on a
# second try a minute after the pass. Anything else that is not a success --
# 403 from a site that refuses robots, 429, a 5xx, a timeout, a link dead on
# the first try only -- is printed as unjudged and does not fail, because a
# weekly job that goes red on someone else's outage teaches its reader to
# ignore it.
#
# A URI in NOT_A_PAGE is not fetched: the docs quote it as a value a tool
# reads, and its host serves no page at it.
#
# NOT checked: whether the page still says what the doc cites it for, and
# whether a #fragment still names a heading. Only a reader catches those.
#
# Usage:  bash scripts/docs/check_external_links.sh   # from anywhere; needs network
# Exit:   0 no dead link, 1 a dead link, a stale NOT_A_PAGE entry, or a failed
#         self-test.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

# URIs the docs quote as values, each answering 404 by design. An entry that no
# tracked markdown holds fails the run, so the list cannot outlive its text.
NOT_A_PAGE=(
    # The Google Maven repository base android/app/build.gradle names in each
    # module's SPDX record; the modules live at paths below it.
    https://dl.google.com/dl/android/maven2
    # The predicate type `gh attestation verify` matches the SPDX attestation on.
    https://spdx.dev/Document/v2.3
)

# A DNS or CDN fault on the runner's side can last a minute; a link is dead
# only when it is dead again after one.
RECHECK_DELAY_SECONDS=60

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

# Fetch a URL, following redirects; set code and curl_status.
fetch() {
    code="$(curl -sS -o /dev/null -L --max-time 30 --retry 2 \
        -A 'Mozilla/5.0 (compatible; r47zen-docs-link-check)' \
        -w '%{http_code}' "$1" 2>/dev/null)"
    curl_status=$?
}

mapfile -t urls < <(git ls-files -z '*.md' | xargs -0 grep -ohE 'https?://[^] )>`"]+' |
    sed -E 's/[.,;:]+$//' | sort -u)
if [[ ${#urls[@]} -eq 0 ]]; then
    echo "check_external_links: no external link found (is this a git checkout?)" >&2
    exit 1
fi

declare -A quoted=() skip=()
for url in "${urls[@]}"; do
    quoted["$url"]=1
done
stale=0
for uri in "${NOT_A_PAGE[@]}"; do
    skip["$uri"]=1
    if [[ -z "${quoted[$uri]:-}" ]]; then
        echo "STALE     no tracked markdown quotes $uri; remove it from NOT_A_PAGE"
        stale=$((stale + 1))
    fi
done

fetched=0
dead=0
unjudged=0
suspects=()
for url in "${urls[@]}"; do
    [[ -n "${skip[$url]:-}" ]] && continue
    fetch "$url"
    fetched=$((fetched + 1))
    case "$(classify "$code" "$curl_status")" in
        dead)
            suspects+=("$url")
            ;;
        unjudged)
            echo "UNJUDGED  $code (curl $curl_status) $url"
            unjudged=$((unjudged + 1))
            ;;
    esac
done

if [[ ${#suspects[@]} -gt 0 ]]; then
    sleep "$RECHECK_DELAY_SECONDS"
    for url in "${suspects[@]}"; do
        fetch "$url"
        if [[ "$(classify "$code" "$curl_status")" == dead ]]; then
            echo "DEAD      $code $url"
            dead=$((dead + 1))
        else
            echo "UNJUDGED  dead once, then $code (curl $curl_status) $url"
            unjudged=$((unjudged + 1))
        fi
    done
fi

echo "external links: $fetched checked, $dead dead, $unjudged unjudged, $stale stale"
[[ "$dead" -eq 0 && "$stale" -eq 0 ]]
