#!/bin/bash

# Contract: scripts/upstream-sync/upstream.sh sync, run against a throwaway
# upstream repository and a throwaway copy of this repo:
#
# - it never writes a path this repo owns: tracked edits, staged and not,
#   survive a sync, with no --force, and upstream's own AGENTS.md never lands
# - a forward sync writes the files that changed, with the current time, keeps
#   an unchanged file's mtime, and removes the files the commit no longer holds
# - it refuses to overwrite a hydrated file edited since the last sync, or a
#   file of yours where the commit adds one, unless --force, and writes nothing
#   when it refuses; a path in UPSTREAM_BUILD_REWRITTEN_PATHS is not an edit
# - a sync that stops halfway leaves the tracked tree clean and the lock as it
#   was, and the next sync completes it without --force
# - export-ignore'd files never reach the tree, and an executable keeps its bit
#
# Pure host test, offline: the upstream URL is a file:// repository.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
unset R47_UPSTREAM_SOURCE_FILE R47_UPSTREAM_LOCK_FILE R47_SYNC_TEST_FAIL_AFTER_WRITES

# Run from a pre-commit hook, git exports GIT_INDEX_FILE and the other
# repository-local variables, so the git commands below, and the sync's own,
# would rewrite this repository's commit index. githooks(5): clear them before
# driving a foreign repository, as run_version_naming_contract.sh does.
mapfile -t git_local_vars < <(git rev-parse --local-env-vars)
unset "${git_local_vars[@]}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
UP="$WORK/upstream"
ME="$WORK/repo"
GIT=(git -c user.email=ci@example.com -c user.name=ci -c init.defaultBranch=main)
failures=()
check() {
    if eval "$2"; then
        printf '  ok   %s\n' "$1"
    else
        printf '  FAIL %s\n' "$1" >&2
        failures+=("$1")
    fi
}

# Upstream: C1, then C2, which changes a.c, removes old.c, adds new.c.
"${GIT[@]}" init -q "$UP"
mkdir -p "$UP/src/c47" "$UP/tools" "$UP/docs" "$UP/res/testPgms"
printf 'all:\n' >"$UP/Makefile"
printf 'committed programs\n' >"$UP/res/testPgms/testPgms.bin"
printf 'a1\n' >"$UP/src/c47/a.c"
printf 'old\n' >"$UP/src/c47/old.c"
printf 'upstream agents\n' >"$UP/AGENTS.md"
printf 'kept out\n' >"$UP/docs/ignored.txt"
printf 'docs/ignored.txt export-ignore\n' >"$UP/.gitattributes"
printf '#!/bin/sh\n' >"$UP/tools/run.sh"
chmod +x "$UP/tools/run.sh"
"${GIT[@]}" -C "$UP" add -A
"${GIT[@]}" -C "$UP" commit -qm c1
C1="$(git -C "$UP" rev-parse HEAD)"
printf 'a2\n' >"$UP/src/c47/a.c"
"${GIT[@]}" -C "$UP" rm -q src/c47/old.c
printf 'new\n' >"$UP/src/c47/new.c"
"${GIT[@]}" -C "$UP" add -A
"${GIT[@]}" -C "$UP" commit -qm c2
C2="$(git -C "$UP" rev-parse HEAD)"
git -C "$UP" config uploadpack.allowAnySHA1InWant true

# A fresh copy of this repo's sync, with its own tracked AGENTS.md and README.md.
fresh_repo() {
    rm -rf "$ME"
    mkdir -p "$ME/scripts/upstream-sync" "$ME/scripts/lib"
    cp "$PROJECT_ROOT/scripts/upstream-sync/upstream.sh" "$ME/scripts/upstream-sync/"
    cp "$PROJECT_ROOT/scripts/lib/common.sh" "$ME/scripts/lib/"
    printf 'upstream_url=file://%s\nupstream_ref=HEAD\n' "$UP" >"$ME/upstream.source"
    printf 'our agents\n' >"$ME/AGENTS.md"
    printf 'our readme\n' >"$ME/README.md"
    "${GIT[@]}" init -q "$ME"
    "${GIT[@]}" -C "$ME" add -A
    "${GIT[@]}" -C "$ME" commit -qm repo
}
sync_to() {
    bash "$ME/scripts/upstream-sync/upstream.sh" sync --commit "$@" >"$WORK/out" 2>&1
}
# Both are called from the check strings, which check() evaluates.
# shellcheck disable=SC2329
tracked_status() { git -C "$ME" status --porcelain --untracked-files=no; }
# shellcheck disable=SC2329
hydrated() { bash "$ME/scripts/upstream-sync/upstream.sh" hydrated-commit; }

echo "first sync, with tracked edits staged and not"
fresh_repo
printf 'my agents\n' >"$ME/AGENTS.md"
printf 'my readme\n' >"$ME/README.md"
git -C "$ME" add README.md
check "a sync with tracked edits needs no --force" "sync_to $C1"
check "the unstaged edit survives" "[ \"\$(cat $ME/AGENTS.md)\" = 'my agents' ]"
check "the staged edit survives" "[ \"\$(git -C $ME show :README.md)\" = 'my readme' ]"
check "upstream's tree is written" "[ \"\$(cat $ME/src/c47/a.c)\" = a1 ]"
check "an export-ignore'd file stays out" "[ ! -e $ME/docs/ignored.txt ]"
check "an executable keeps its bit" "[ -x $ME/tools/run.sh ]"
check "with no earlier record, it says so" "grep -q 'no record of an earlier sync' $WORK/out"
check "it records the commit" "[ \"\$(hydrated)\" = $C1 ]"
check "--force leaves both tracked edits too" "sync_to $C2 --force && [ \"\$(cat $ME/AGENTS.md)\" = 'my agents' ] && [ \"\$(git -C $ME show :README.md)\" = 'my readme' ]"

echo "forward sync"
fresh_repo
sync_to "$C1"
touch -d '2001-01-01' "$ME/Makefile" "$ME/src/c47/a.c"
check "a forward sync completes" "sync_to $C2"
check "a changed file is written" "[ \"\$(cat $ME/src/c47/a.c)\" = a2 ]"
check "a changed file gets the current time" "[ $ME/src/c47/a.c -nt $ME/Makefile ]"
check "an unchanged file keeps its mtime" "[ \"\$(date -r $ME/Makefile +%Y)\" = 2001 ]"
check "a file the commit no longer holds is removed" "[ ! -e $ME/src/c47/old.c ]"
check "a file the commit adds is written" "[ \"\$(cat $ME/src/c47/new.c)\" = new ]"
check "a backward sync brings the file back" "sync_to $C1 && [ -e $ME/src/c47/old.c ] && [ ! -e $ME/src/c47/new.c ]"

echo "edits in the hydrated tree"
fresh_repo
sync_to "$C1"
printf 'my edit\n' >"$ME/src/c47/a.c"
check "a sync over an edited hydrated file refuses" "! sync_to $C2"
check "it names the file" "grep -q 'edited: src/c47/a.c' $WORK/out"
check "it writes nothing when it refuses" "[ \"\$(cat $ME/src/c47/a.c)\" = 'my edit' ] && [ -e $ME/src/c47/old.c ]"
check "the record still names the old commit" "[ \"\$(hydrated)\" = $C1 ]"
check "--force overwrites it" "sync_to $C2 --force && [ \"\$(cat $ME/src/c47/a.c)\" = a2 ]"
fresh_repo
sync_to "$C1"
printf 'mine\n' >"$ME/src/c47/new.c"
check "a file of yours where the commit adds one refuses" "! sync_to $C2 && [ \"\$(cat $ME/src/c47/new.c)\" = mine ]"
printf 'loose\n' >"$ME/src/c47/loose.c"
rm "$ME/src/c47/new.c"
check "a file of yours at no upstream path stays" "sync_to $C2 && [ \"\$(cat $ME/src/c47/loose.c)\" = loose ]"
printf 'built programs\n' >"$ME/res/testPgms/testPgms.bin"
check "a path a build rewrites is not an edit" "sync_to $C1 && [ \"\$(cat $ME/res/testPgms/testPgms.bin)\" = 'committed programs' ]"

echo "a sync that stops halfway"
fresh_repo
sync_to "$C1"
check "a sync stopped after one write fails" "! R47_SYNC_TEST_FAIL_AFTER_WRITES=1 sync_to $C2 --write-lock"
check "the tracked tree stays clean" "[ -z \"\$(tracked_status)\" ]"
check "the lock is not written" "[ ! -e $ME/upstream.lock ]"
check "no commit is recorded" "[ -z \"\$(hydrated)\" ]"
check "the next sync completes it without --force" "sync_to $C2 --write-lock"
check "the tree is the commit's" "[ \"\$(cat $ME/src/c47/a.c)\" = a2 ] && [ ! -e $ME/src/c47/old.c ] && [ -e $ME/src/c47/new.c ]"
check "the lock is written" "grep -q \"upstream_commit=$C2\" $ME/upstream.lock"
check "upstream's AGENTS.md never landed" "[ \"\$(cat $ME/AGENTS.md)\" = 'our agents' ]"

if [ "${#failures[@]}" -gt 0 ]; then
    contract_fail "the sync broke ${#failures[@]} rule(s):" "$(printf '%s\n' "${failures[@]}")"
fi
contract_pass "upstream.sh sync keeps this repo's paths, refuses hydrated edits, removes what upstream removed, and resumes a stopped sync."
