#!/bin/bash

# Print the upstream C47 release the core is, or nothing when the core is no
# release. The release is the VERSION1 string in src/c47/defines.h (for example
# 00.109.05.00b2), and the core is that release only when upstream's tag of the
# same name points at the core commit. Upstream sets VERSION1 when it cuts a
# release and leaves it until the next, so every commit in between carries the
# last release's number: 2a664c67 reads 00.109.05.00b2 and is 708 commits past
# that tag. Upstream's own build shows such a commit as "custom build <commit>"
# (src/c47/version.h.in), and an empty output tells a caller to do the same.
#
# It never fails a build: upstream owns the file and may reshape the define, and
# a tag lookup that fails prints nothing, the label of a commit with no release.
#
# Usage: scripts/android/read_upstream_version.sh <path to defines.h> \
#   <upstream url> <full upstream commit>

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

defines_file="${1-}"
upstream_url="${2-}"
upstream_commit="${3-}"
if [[ -z "$defines_file" || ! -f "$defines_file" ]]; then
    exit 0
fi

# The second sed reads all input, so the first never dies of SIGPIPE.
value="$(sed -n 's/^[[:space:]]*#[[:space:]]*define[[:space:]]\{1,\}VERSION1[[:space:]]\{1,\}"\([^"]*\)".*/\1/p' "$defines_file" | sed -n '1p')"

if [[ ! "$value" =~ ^[0-9A-Za-z.-]{1,40}$ ]]; then
    exit 0
fi
if [[ -z "$upstream_url" || ! "$upstream_commit" =~ ^[0-9a-f]{40}$ ]]; then
    exit 0
fi

# An annotated tag lists twice: its own object, then as <tag>^{} the commit it
# points at. A lightweight tag lists once, as the commit.
if ! tags="$(retry_with_backoff "git ls-remote $upstream_url" \
    git ls-remote --tags "$upstream_url" "refs/tags/$value" "refs/tags/$value^{}")"; then
    echo "No tag lookup at $upstream_url; the core is named as a commit with no release." >&2
    exit 0
fi
peeled="$(awk -v ref="refs/tags/$value^{}" '$2 == ref { print $1 }' <<<"$tags")"
plain="$(awk -v ref="refs/tags/$value" '$2 == ref { print $1 }' <<<"$tags")"

if [[ "${peeled:-$plain}" == "$upstream_commit" ]]; then
    printf '%s\n' "$value"
fi
