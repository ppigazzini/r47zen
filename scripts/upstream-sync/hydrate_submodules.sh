#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

upstream_commit="${1:-}"
upstream_url="${2:-}"
[ -n "$upstream_commit" ] || fail "Usage: scripts/upstream-sync/hydrate_submodules.sh <upstream-commit>"

if ! git -C "$PROJECT_ROOT" cat-file -e "$upstream_commit^{commit}" 2>/dev/null; then
    [ -n "$upstream_url" ] ||
        fail "Commit $upstream_commit is not available locally. Provide an upstream URL as the second argument or run upstream sync first"

    hydrate_remote_name="r47-c43-upstream-hydrate"
    if git -C "$PROJECT_ROOT" remote | grep -qx "$hydrate_remote_name"; then
        git -C "$PROJECT_ROOT" remote set-url "$hydrate_remote_name" "$upstream_url"
    else
        git -C "$PROJECT_ROOT" remote add "$hydrate_remote_name" "$upstream_url"
    fi

    git -C "$PROJECT_ROOT" fetch --depth 1 "$hydrate_remote_name" "$upstream_commit"
    git -C "$PROJECT_ROOT" cat-file -e "$upstream_commit^{commit}" ||
        fail "Commit $upstream_commit could not be fetched from $upstream_url"
fi

gitlinks=$(git -C "$PROJECT_ROOT" ls-tree -r "$upstream_commit" |
    awk '$1 == "160000" { print $3 "\t" $4 }')

[ -n "$gitlinks" ] || exit 0

gitmodules_tmp=$(mktemp)
trap 'rm -f "$gitmodules_tmp"' EXIT
git -C "$PROJECT_ROOT" show "$upstream_commit:.gitmodules" >"$gitmodules_tmp" 2>/dev/null ||
    fail "Commit $upstream_commit contains submodules but .gitmodules is missing"

declare -A path_to_name=()
while IFS= read -r entry; do
    key=${entry%% *}
    value=${entry#* }
    module_name=${key#submodule.}
    module_name=${module_name%.path}
    path_to_name["$value"]="$module_name"
done < <(git config -f "$gitmodules_tmp" --get-regexp '^submodule\..*\.path$')

while IFS=$'\t' read -r submodule_commit submodule_path; do
    [ -n "$submodule_commit" ] || continue
    [ -n "$submodule_path" ] || continue

    module_name="${path_to_name[$submodule_path]:-}"
    [ -n "$module_name" ] ||
        fail "Unable to map submodule path $submodule_path from commit .gitmodules"

    module_url=$(git config -f "$gitmodules_tmp" --get "submodule.${module_name}.url" || true)
    [ -n "$module_url" ] ||
        fail "Missing URL for submodule $module_name at path $submodule_path"

    target_path="$PROJECT_ROOT/$submodule_path"
    rm -rf "$target_path"
    mkdir -p "$target_path"

    git -C "$target_path" init -q
    git -C "$target_path" remote add origin "$module_url"
    if ! git -C "$target_path" fetch --depth 1 origin "$submodule_commit"; then
        git -C "$target_path" fetch origin "$submodule_commit"
    fi
    git -C "$target_path" checkout --detach "$submodule_commit" >/dev/null
done <<<"$gitlinks"
