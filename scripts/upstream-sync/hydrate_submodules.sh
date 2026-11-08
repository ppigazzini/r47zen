#!/bin/bash
# scripts/upstream-sync/hydrate_submodules.sh <upstream-commit> [<upstream-url>]
#
# Check out each submodule of an upstream commit at the commit it pins, which
# git archive, and so upstream.sh sync, leaves out. A submodule already checked
# out at that commit is left alone, edits and all, with no network. Any other is
# fetched beside the old checkout and swapped in only once checked out, so a
# failed fetch keeps the old one. run_upstream_sync_contract.sh holds both.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

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
    remotes="$(git -C "$PROJECT_ROOT" remote)"
    if grep -qx "$hydrate_remote_name" <<<"$remotes"; then
        git -C "$PROJECT_ROOT" remote set-url "$hydrate_remote_name" "$upstream_url"
    else
        git -C "$PROJECT_ROOT" remote add "$hydrate_remote_name" "$upstream_url"
    fi

    retry_with_backoff "Fetch upstream core ${upstream_commit}" \
        git -C "$PROJECT_ROOT" fetch --depth 1 "$hydrate_remote_name" "$upstream_commit"
    git -C "$PROJECT_ROOT" cat-file -e "$upstream_commit^{commit}" ||
        fail "Commit $upstream_commit could not be fetched from $upstream_url"
fi

# Split on TAB so a submodule path containing spaces is preserved: git ls-tree
# formats each line as "<mode> <type> <object>\t<path>", so the metadata is
# field 1 (space-separated) and the full path is field 2.
gitlinks=$(git -C "$PROJECT_ROOT" ls-tree -r "$upstream_commit" |
    awk -F'\t' '$1 ~ /^160000 / { split($1, meta, " "); print meta[3] "\t" $2 }')

[ -n "$gitlinks" ] || exit 0

gitmodules_tmp=$(mktemp)
staging_path=""
trap 'rm -f "$gitmodules_tmp"; [ -z "$staging_path" ] || rm -rf "$staging_path"' EXIT
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

    # A checkout already at the commit stays, edits and all, and needs no
    # network. From a directory that is not its own repository, rev-parse
    # finds the parent's, so the git-dir test keeps that from matching.
    if [ "$(git -C "$target_path" rev-parse --git-dir 2>/dev/null)" = ".git" ] &&
        [ "$(git -C "$target_path" rev-parse HEAD 2>/dev/null)" = "$submodule_commit" ]; then
        echo "--- Submodule $submodule_path already at $submodule_commit ---"
        continue
    fi

    # Fetch into a directory beside it and swap it in once checked out, so a
    # failed fetch, offline or not, leaves the old checkout in place.
    staging_path="${target_path}.r47-hydrate"
    rm -rf "$staging_path"
    mkdir -p "$staging_path"
    git -C "$staging_path" init -q
    git -C "$staging_path" remote add origin "$module_url"
    if ! retry_with_backoff "Fetch submodule ${module_name} (shallow)" \
        git -C "$staging_path" fetch --depth 1 origin "$submodule_commit"; then
        retry_with_backoff "Fetch submodule ${module_name} (full)" \
            git -C "$staging_path" fetch origin "$submodule_commit"
    fi
    git -C "$staging_path" checkout --detach "$submodule_commit" >/dev/null
    rm -rf "$target_path"
    mv "$staging_path" "$target_path"
    staging_path=""
done <<<"$gitlinks"
