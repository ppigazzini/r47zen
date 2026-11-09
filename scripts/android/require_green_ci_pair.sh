#!/bin/bash
# Refuse a release whose commit pair android-ci has not passed.
#
# The release resolves upstream HEAD at dispatch, so a green android-ci on the
# overlay commit says nothing about a core that moved since. android-ci publishes
# the -dev pre-release r47zen-<upstream8>-<android8>-dev, with its APK, only after
# every lane ci-required names passed on exactly that pair, so that release with
# an APK on it is the proof. This reads `gh release view <tag> --json
# assets` from a file, written by the caller, so the contract can feed it
# fixtures; a missing or empty file means the tag does not exist.
#
# Usage: require_green_ci_pair.sh --upstream-commit <sha> --android-commit <sha>
#            --release-json <file> [--warn-only]
#
# --warn-only, for a dry run off main, where android-ci publishes no -dev
# release, reports the same finding and passes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

upstream_commit=""
android_commit=""
release_json=""
warn_only=false
while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --upstream-commit)
            upstream_commit="${2-}"
            shift 2
            ;;
        --android-commit)
            android_commit="${2-}"
            shift 2
            ;;
        --release-json)
            release_json="${2-}"
            shift 2
            ;;
        --warn-only)
            warn_only=true
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done
if [[ ! "$upstream_commit" =~ ^[0-9a-f]{40}$ || ! "$android_commit" =~ ^[0-9a-f]{40}$ ||
    -z "$release_json" ]]; then
    echo "usage: $0 --upstream-commit <sha> --android-commit <sha> --release-json <file> [--warn-only]" >&2
    exit 2
fi

eval "$(bash "$SCRIPT_DIR/resolve_android_artifact_metadata.sh" \
    --upstream-commit "$upstream_commit" --android-commit "$android_commit")"
tag="$R47_ANDROID_DEV_RELEASE_TAG"

apks=0
if [[ -s "$release_json" ]]; then
    apks="$(python3 -c '
import json, sys
assets = json.load(open(sys.argv[1])).get("assets") or []
print(sum(1 for a in assets if str(a.get("name", "")).endswith(".apk")))
' "$release_json")"
fi

if [[ "$apks" -ge 1 ]]; then
    echo "OK: ${tag} carries an APK, so android-ci passed on upstream ${upstream_commit:0:8} and overlay ${android_commit:0:8}."
    exit 0
fi

message="No -dev release ${tag} with an APK: android-ci has not passed on upstream ${upstream_commit:0:8} with overlay ${android_commit:0:8}. Run android-ci on main for this pair, or pin upstream_commit to a pair it passed."
if [[ "$warn_only" == true ]]; then
    echo "::warning::${message}"
    exit 0
fi
echo "$message" >&2
exit 1
