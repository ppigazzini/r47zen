#!/bin/bash

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/android/resolve_android_artifact_metadata.sh \
      --upstream-commit <sha-or-token> \
      --android-commit <sha-or-token>

Outputs shell assignments for the repo-owned Android artifact naming contract.
EOF
}

upstream_commit=""
android_commit=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --upstream-commit)
            upstream_commit="${2-}"
            shift 2
            ;;
        --android-commit)
            android_commit="${2-}"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

normalize_commit_token() {
    local value="$1"

    if [[ -z "$value" ]]; then
        printf 'unknown\n'
        return 0
    fi

    printf '%.8s\n' "$value"
}

upstream_short_commit=$(normalize_commit_token "$upstream_commit")
android_short_commit=$(normalize_commit_token "$android_commit")
artifact_stem="r47-android-${upstream_short_commit}-${android_short_commit}"

printf 'R47_ANDROID_UPSTREAM_SHORT_COMMIT=%q\n' "$upstream_short_commit"
printf 'R47_ANDROID_SOURCE_SHORT_COMMIT=%q\n' "$android_short_commit"
printf 'R47_ANDROID_ARTIFACT_STEM=%q\n' "$artifact_stem"
printf 'R47_ANDROID_TEST_ARTIFACT_STEM=%q\n' "r47-android-tests-${upstream_short_commit}-${android_short_commit}"
printf 'R47_ANDROID_DEBUG_APK_NAME=%q\n' "${artifact_stem}-debug.apk"
printf 'R47_ANDROID_RELEASE_TAG=%q\n' "$artifact_stem"
printf 'R47_ANDROID_RELEASE_TITLE=%q\n' "$artifact_stem"