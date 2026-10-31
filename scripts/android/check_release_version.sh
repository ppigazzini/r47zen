#!/bin/bash

# Hold a production release version to the rules in
# android/docs/dev/10-writing.md#version-numbers: every release gets a new
# major.minor.patch, a release whose app code changed since the previous
# release bumps MINOR and resets PATCH (or bumps MAJOR), and any other release
# bumps PATCH. App code is android/app/src/main/, what this repo ships in the
# APK. A release that changes only the upstream firmware, CI, the build, tests,
# or docs is a PATCH release.
#
# The previous release is named by its version, from the
# "- Android version <name> (<code>)" line resolve_previous_release.sh reads,
# and by the r47zen commit it was built from, the android_source_commit in its
# evidence. The diff needs that commit in the local history, so a CI checkout
# needs fetch-depth 0.
#
# A previous name with a "-" suffix is a SemVer pre-release of its
# major.minor.patch and sorts before it: every release before these rules was
# one (0.1.13-signed.2026102901 precedes 0.1.13). From a pre-release the steps
# are npm semver's inc(): PATCH drops the suffix (0.1.13-x -> 0.1.13), MINOR
# drops it when PATCH is 0 and bumps MINOR otherwise (0.1.13-x -> 0.2.0,
# 0.2.0-x -> 0.2.0), and MAJOR drops it when MINOR and PATCH are 0.
#
# Prints app_changed=<true|false> and expected=<version>, then exits 0 when
# --version is a valid next version and 1 with the reason on stderr otherwise.
# With no previous release (an empty --previous-version) any major.minor.patch
# passes.

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/android/check_release_version.sh \
      --version <major.minor.patch> \
      --previous-version <name or empty> \
      --previous-commit <sha or empty> \
      [--head <rev>] [--repo <dir>]
EOF
}

version=""
previous_version=""
previous_commit=""
head_rev="HEAD"
repo="."

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)
            version="${2-}"
            shift 2
            ;;
        --previous-version)
            previous_version="${2-}"
            shift 2
            ;;
        --previous-commit)
            previous_commit="${2-}"
            shift 2
            ;;
        --head)
            head_rev="${2-}"
            shift 2
            ;;
        --repo)
            repo="${2-}"
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

app_code_path="android/app/src/main/"

# SemVer 2.0.0 item 2: three non-negative integers without leading zeroes.
semver_re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
if [[ ! "$version" =~ $semver_re ]]; then
    echo "Version '${version}' is not major.minor.patch (three integers, no leading zeros, no suffix)." >&2
    exit 1
fi

if [[ -z "$previous_version" ]]; then
    echo "app_changed=unknown"
    echo "expected=${version}"
    exit 0
fi

previous_re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$'
if [[ ! "$previous_version" =~ $previous_re ]]; then
    echo "The previous release version '${previous_version}' is not major.minor.patch with an optional pre-release suffix." >&2
    exit 1
fi
previous_major="${BASH_REMATCH[1]}"
previous_minor="${BASH_REMATCH[2]}"
previous_patch="${BASH_REMATCH[3]}"
previous_is_prerelease=false
if [[ -n "${BASH_REMATCH[4]}" ]]; then
    previous_is_prerelease=true
fi

if [[ "$previous_is_prerelease" == "true" ]]; then
    patch_step="${previous_major}.${previous_minor}.${previous_patch}"
else
    patch_step="${previous_major}.${previous_minor}.$((previous_patch + 1))"
fi
if [[ "$previous_is_prerelease" == "true" && "$previous_patch" -eq 0 ]]; then
    minor_step="${previous_major}.${previous_minor}.0"
else
    minor_step="${previous_major}.$((previous_minor + 1)).0"
fi
if [[ "$previous_is_prerelease" == "true" && "$previous_minor" -eq 0 && "$previous_patch" -eq 0 ]]; then
    major_step="${previous_major}.0.0"
else
    major_step="$((previous_major + 1)).0.0"
fi

if [[ ! "$previous_commit" =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "The previous release ${previous_version} names no r47zen commit ('${previous_commit}')." >&2
    exit 1
fi
if ! git -C "$repo" cat-file -e "${previous_commit}^{commit}" 2>/dev/null; then
    echo "Commit ${previous_commit} of the previous release ${previous_version} is not in the local history; a CI checkout needs fetch-depth 0." >&2
    exit 1
fi

changed="$(git -C "$repo" diff --name-only "$previous_commit" "$head_rev" -- "$app_code_path")"

if [[ -n "$changed" ]]; then
    echo "app_changed=true"
    echo "expected=${minor_step}"
    if [[ "$version" == "$minor_step" || "$version" == "$major_step" ]]; then
        exit 0
    fi
    count="$(wc -l <<<"$changed" | tr -d ' ')"
    {
        echo "App code (${app_code_path}) changed since ${previous_version}, ${count} file(s), for example:"
        head -n 5 <<<"$changed" | sed 's/^/  /'
        echo "The release takes the MINOR step, PATCH reset: ${minor_step} (or ${major_step} for a MAJOR step), not ${version}."
    } >&2
    exit 1
fi

echo "app_changed=false"
echo "expected=${patch_step}"
if [[ "$version" == "$patch_step" ]]; then
    exit 0
fi
echo "App code (${app_code_path}) is unchanged since ${previous_version}: the release takes the PATCH step, ${patch_step}, not ${version}." >&2
exit 1
