#!/bin/bash

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/android/resolve_android_artifact_metadata.sh \
      --upstream-commit <sha-or-token> \
      --android-commit <sha-or-token> \
      [--version <version>]

Outputs shell assignments for the repo-owned Android artifact naming contract.

A published file is named r47zen_<version>_<upstream8>_<android8>.<ext>: the
version first, then both short commits, joined by "_". SemVer identifiers
allow only [0-9A-Za-z-], so a SemVer reader stops at "_" and reads the version
field exactly. --version is the production version (0.1.15) or the dev
pre-release version (0.1.16-53.1); without it the versioned names carry
"unversioned". Tags and workflow artifacts keep the commit-only stem
r47zen-<upstream8>-<android8>: the dev skip gate keys on its tag.
EOF
}

upstream_commit=""
android_commit=""
version=""

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
        --version)
            version="${2-}"
            shift 2
            ;;
        --help | -h)
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

if [[ -z "$version" ]]; then
    version="unversioned"
elif [[ ! "$version" =~ ^[0-9A-Za-z.-]+$ ]]; then
    echo "Version '${version}' may hold only [0-9A-Za-z.-]: '_' separates the file name fields." >&2
    exit 1
fi

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
artifact_stem="r47zen-${upstream_short_commit}-${android_short_commit}"
dev_artifact_stem="${artifact_stem}-dev"
versioned_stem="r47zen_${version}_${upstream_short_commit}_${android_short_commit}"

printf 'R47_ANDROID_UPSTREAM_SHORT_COMMIT=%q\n' "$upstream_short_commit"
printf 'R47_ANDROID_SOURCE_SHORT_COMMIT=%q\n' "$android_short_commit"
printf 'R47_ANDROID_ARTIFACT_STEM=%q\n' "$artifact_stem"
printf 'R47_ANDROID_TEST_ARTIFACT_STEM=%q\n' "r47zen-tests-${upstream_short_commit}-${android_short_commit}"
printf 'R47_ANDROID_DEBUG_APK_NAME=%q\n' "${artifact_stem}-debug.apk"
printf 'R47_ANDROID_DEV_ARTIFACT_STEM=%q\n' "$dev_artifact_stem"
printf 'R47_ANDROID_DEV_APK_NAME=%q\n' "${versioned_stem}.apk"
printf 'R47_ANDROID_DEV_RELEASE_TAG=%q\n' "$dev_artifact_stem"
printf 'R47_ANDROID_DEV_RELEASE_TITLE=%q\n' "$versioned_stem"
printf 'R47_ANDROID_RELEASE_ARTIFACT_STEM=%q\n' "$versioned_stem"
printf 'R47_ANDROID_RELEASE_AAB_NAME=%q\n' "${versioned_stem}.aab"
printf 'R47_ANDROID_RELEASE_APK_NAME=%q\n' "${versioned_stem}.apk"
printf 'R47_ANDROID_RELEASE_TAG=%q\n' "$artifact_stem"
printf 'R47_ANDROID_RELEASE_TITLE=%q\n' "$artifact_stem"
