#!/bin/bash
# Write a tar.gz of the upstream core's source at one commit, for a release to
# carry beside the binary built from it.
#
# A published APK is object code of the GPL-3.0 core. Section 6(d) lets a
# distributor point at source on another party's server only while it ensures
# that server keeps it, which this repository cannot ensure for upstream's
# GitLab, so each published binary carries the core's tree. The archive holds the
# commit's tree without docs/, the manuals, from which nothing in the app is
# built: 92 MB of the 104 MB a whole archive of upstream's 8e872462c9 takes
# compressed, against 12 MB without. This repository's own source is the
# release tag's. Only git runs: no upstream code executes, so a job holding a
# release token may run it.
#
# Usage: archive_core_source.sh <upstream url> <40-hex commit> <output.tar.gz>
# R47_ARCHIVE_RETRY_SECONDS (10) scales the wait between the three fetch attempts.

set -euo pipefail

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

[[ "$#" -eq 3 ]] || fail "usage: $0 <upstream url> <40-hex commit> <output.tar.gz>"
url="$1"
commit="$2"
output="$3"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "'${commit}' is not a full commit hash."
mkdir -p "$(dirname "$output")"
output="$(cd "$(dirname "$output")" && pwd)/$(basename "$output")"

# The repository below is this script's own: a caller's GIT_DIR or GIT_INDEX_FILE,
# as a git hook exports, would aim its git commands at the caller's repository.
mapfile -t git_local_vars < <(git rev-parse --local-env-vars)
unset "${git_local_vars[@]}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git init -q "$work/core"
[[ "$(git -C "$work/core" rev-parse --absolute-git-dir)" == "$work/core/.git" ]] ||
    fail "git resolves the scratch repository to another one; refusing to touch it."
fetched=false
for attempt in 1 2 3; do
    if git -C "$work/core" fetch -q --depth 1 "$url" "$commit"; then
        fetched=true
        break
    fi
    echo "Fetch attempt ${attempt} of ${commit} failed." >&2
    sleep $((attempt * ${R47_ARCHIVE_RETRY_SECONDS:-10}))
done
[[ "$fetched" == true ]] || fail "could not fetch ${commit} from ${url}."
[[ "$(git -C "$work/core" rev-parse FETCH_HEAD)" == "$commit" ]] ||
    fail "${url} answered ${commit} with another commit."

git -C "$work/core" archive --format=tar.gz --prefix="c47-${commit}/" \
    -o "$output" FETCH_HEAD -- . ':(exclude)docs'
echo "Wrote $(basename "$output"): the core at ${commit}, without docs/, $(wc -c <"$output") bytes."
