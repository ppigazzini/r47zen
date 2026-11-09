#!/bin/bash
# Contract: .github/workflows/actions.lock covers every remote action each workflow
# runs, offline, as gh-actions-lock's --verify-local does without the
# extension: every remote `uses:` of a workflow, and of each local composite
# action it calls, directly or through another, has an entry under that workflow;
# every workflow is in the lockfile and the lockfile names no other. An entry names
# the repository, so owner/repo/path@sha counts as owner/repo@sha. The tool does not
# follow ./ actions, so a remote action a composite gains needs a never-run step in
# each workflow that calls it; this contract is what notices. The Actions Lock
# workflow runs the extension's --verify, which also checks each pin upstream.
# The checker proves it fails on seeded copies first. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
LOCKFILE="$WORKFLOW_DIR/actions.lock"
[ -f "$LOCKFILE" ] || contract_fail "no $LOCKFILE"

problems() {
    python3 - "$PROJECT_ROOT" "$1" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
lock_text = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
uses_line = re.compile(r"^\s*(?:-\s+)?uses:\s*([^\s#]+)")


def remote_refs(path, seen):
    """Remote owner/repo@sha refs a workflow or action file runs, through local actions."""
    refs = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("#"):
            continue
        match = uses_line.match(line)
        if not match:
            continue
        ref = match.group(1).strip("'\"")
        if ref.startswith("./.github/actions/"):
            action = root / ref[2:] / "action.yml"
            if action not in seen:
                seen.add(action)
                refs |= remote_refs(action, seen)
        elif ref.startswith(("./", "docker://")):
            continue
        else:
            name, _, sha = ref.partition("@")
            refs.add("/".join(name.split("/")[:2]).lower() + "@" + sha)
    return refs


locked = {}
current = None
section = None
for line in lock_text.splitlines():
    if not line.startswith(" "):
        section = line.rstrip(":")
        continue
    if section != "workflows":
        continue
    key = re.match(r"^    '([^']+)':", line)
    if key:
        current = key.group(1)
        locked[current] = set()
        continue
    entry = re.match(r"^        - '([^']+)'", line)
    if entry and current:
        locked[current].add(entry.group(1).lower())

workflows = {
    str(path.relative_to(root)): path
    for path in sorted((root / ".github" / "workflows").glob("*.yml"))
}
for name in sorted(set(locked) - set(workflows)):
    print(f"{name} is in the lockfile but not on disk")
for name, path in workflows.items():
    if name not in locked:
        print(f"{name} is not in the lockfile: run gh actions-lock --ignore-local-actions")
        continue
    for ref in sorted(remote_refs(path, set()) - locked[name]):
        print(f"{name} runs {ref}, which the lockfile does not record for it")
PY
}

found="$(problems "$LOCKFILE")"
[ -z "$found" ] || contract_fail "actions.lock does not cover what the workflows run:" "$found"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
seed() {
    local name="$1" pattern="$2"
    grep -v -- "$pattern" "$LOCKFILE" >"$work/seeded.lock"
    cmp -s "$LOCKFILE" "$work/seeded.lock" && contract_fail "seed '$name' changed nothing; the contract is stale"
    [ -n "$(problems "$work/seeded.lock")" ] || contract_fail "the coverage check passed seed '$name'"
}
# What a composite runs, recorded only through the never-run lock step.
seed 'no setup-android' "^        - 'android-actions/setup-android@"
seed 'no checkout' "^        - 'actions/checkout@"
seed 'a workflow left out' "^    '.github/workflows/shell-lint.yml':"

contract_pass "actions.lock records every remote action each workflow runs, its composites' included."
