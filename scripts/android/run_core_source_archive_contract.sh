#!/bin/bash
# Contract: every published binary carries the core's source.
#
# archive_core_source.sh is run on a local fixture repository over file://: it
# archives the commit it is asked for, not the tip, under c47-<commit>/, with the
# tree's files and without docs/; it refuses a short hash and a commit the
# repository lacks. Then the workflows: android-release.yml's package-core-source
# job archives the commit resolve-upstream-core resolved, and the publish job
# needs it, checks the archive names that commit, attaches it, attests it, and
# keeps it in a re-cut; android-ci.yml's dev publish archives the resolved
# commit, refuses a build naming another, and attaches it. Each wiring check
# also fails a seeded copy. Pure host test; needs git.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
ARCHIVE="$SCRIPT_DIR/archive_core_source.sh"

# Run from a pre-commit hook, git exports GIT_INDEX_FILE and the other
# repository-local variables, so the fixture's git commands could rewrite this
# repository's index. githooks(5): clear them before driving a foreign
# repository, as run_upstream_sync_contract.sh does. A first draft without this,
# run from this worktree's commit hook, left the worktree's index holding the
# fixture's three files and the main clone's core.bare true; two reproductions
# in throwaway worktrees did not repeat it, so the fixture also stops unless git
# resolves it to its own .git.
mapfile -t git_local_vars < <(git rev-parse --local-env-vars)
unset "${git_local_vars[@]}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

commit_all() { git -C "$work/up" -c user.email=c@example.invalid -c user.name=contract commit -qm "$1"; }
git init -q "$work/up"
[ "$(git -C "$work/up" rev-parse --absolute-git-dir)" = "$work/up/.git" ] ||
    contract_fail "git resolves the fixture to another repository; refusing to touch it"
mkdir -p "$work/up/docs" "$work/up/src/c47"
printf 'manual\n' >"$work/up/docs/manual.tex"
printf 'int core;\n' >"$work/up/src/c47/core.c"
printf 'GPL\n' >"$work/up/COPYING"
git -C "$work/up" add -A
commit_all one
wanted="$(git -C "$work/up" rev-parse HEAD)"
printf 'int later;\n' >"$work/up/src/c47/later.c"
git -C "$work/up" add -A
commit_all two

export R47_ARCHIVE_RETRY_SECONDS=0
bash "$ARCHIVE" "file://$work/up" "$wanted" "$work/out/core.tar.gz" >/dev/null ||
    contract_fail "archive_core_source.sh failed on a commit the repository holds"
listing="$(tar -tzf "$work/out/core.tar.gz")"
for path in "c47-$wanted/COPYING" "c47-$wanted/src/c47/core.c"; do
    grep -qx "$path" <<<"$listing" || contract_fail "the archive lacks $path:" "$listing"
done
if grep -q "/docs/\|/later.c" <<<"$listing"; then
    contract_fail "the archive holds docs/ or the tip's file:" "$listing"
fi
[ "$(cut -d/ -f1 <<<"$listing" | sort -u)" == "c47-$wanted" ] ||
    contract_fail "the archive has a top directory other than c47-$wanted:" "$listing"

if bash "$ARCHIVE" "file://$work/up" "${wanted:0:12}" "$work/out/short.tar.gz" >/dev/null 2>&1; then
    contract_fail "archive_core_source.sh took a short hash"
fi
if bash "$ARCHIVE" "file://$work/up" "$(printf '%040d' 0)" "$work/out/absent.tar.gz" >/dev/null 2>&1; then
    contract_fail "archive_core_source.sh passed a commit the repository lacks"
fi
[ ! -e "$work/out/absent.tar.gz" ] || contract_fail "a failed archive left a file"

release_live="$(strip_hash_comments <"$WORKFLOW_DIR/android-release.yml")"
ci_live="$(strip_hash_comments <"$WORKFLOW_DIR/android-ci.yml")"

# shellcheck disable=SC2016 # the workflows' own ${{ }} expressions, matched unexpanded
wiring_problems() {
    local release="$1" ci="$2" package publish dev
    package="$(workflow_job_block /dev/stdin package-core-source <<<"$release")"
    publish="$(workflow_job_block /dev/stdin publish-production-release <<<"$release")"
    dev="$(workflow_job_block /dev/stdin publish-main-snapshot <<<"$ci")"
    grep -q 'UPSTREAM_COMMIT: ${{ needs.resolve-upstream-core.outputs.upstream_commit }}' <<<"$package" &&
        grep -q 'archive_core_source.sh "$UPSTREAM_URL" "$UPSTREAM_COMMIT"' <<<"$package" ||
        echo "package-core-source does not archive the resolved commit"
    grep -Eq '^      - package-core-source$' <<<"$publish" || echo "the publish job does not need package-core-source"
    grep -q '\[\[ "$roots" == "c47-${UPSTREAM_COMMIT}" \]\]' <<<"$publish" ||
        echo "the publish job does not check the archive names the release's core"
    [ "$(grep -c 'release-source/' <<<"$publish")" -ge 4 ] ||
        echo "the publish job does not download, attach, attest, and verify the archive"
    grep -q '"$APK_EVIDENCE" "$AAB_EVIDENCE" "$CORE_SOURCE"' <<<"$publish" ||
        echo "a re-cut would delete the archive it just uploaded"
    grep -q 'archive_core_source.sh "$UPSTREAM_URL" "$UPSTREAM_COMMIT"' <<<"$dev" &&
        grep -q '\[\[ "$built_commit" == "$UPSTREAM_COMMIT" \]\]' <<<"$dev" ||
        echo "the dev publish does not archive the resolved commit, or takes a build naming another"
    grep -q 'release-assets/${{ steps.core-source.outputs.name }}' <<<"$dev" ||
        echo "the dev publish does not attach the archive"
    return 0
}

problems="$(wiring_problems "$release_live" "$ci_live")"
[ -z "$problems" ] || contract_fail "a published binary would ship without its core's source:" "$problems"

seed() {
    local name="$1" file="$2" from="$3" to="$4" release="$release_live" ci="$ci_live"
    if [ "$file" = release ]; then release="$(sed "s|$from|$to|" <<<"$release")"; else ci="$(sed "s|$from|$to|" <<<"$ci")"; fi
    [ "$release$ci" != "$release_live$ci_live" ] || contract_fail "seed '$name' changed nothing; the contract is stale"
    [ -n "$(wiring_problems "$release" "$ci")" ] || contract_fail "the wiring check passed seed '$name'"
}
# shellcheck disable=SC2016 # each seed edits the workflows' text, unexpanded
{
    seed 'no needs' release '^      - package-core-source$' '      - verify-signed-artifacts'
    seed 'no root check' release '\[\[ "$roots" == ' '[[ "$roots" != '
    seed 'recut deletes it' release ' "$CORE_SOURCE")' ')'
    seed 'dev no attach' ci 'release-assets/${{ steps.core-source.outputs.name }}' 'release-assets/none'
    seed 'dev takes any build' ci '\[\[ "$built_commit" == "$UPSTREAM_COMMIT" \]\]' 'true'
}

contract_pass "every published binary carries the source of the core it is built from."
