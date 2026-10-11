#!/bin/bash

# Contract: scripts/docs/check_commit_message.sh holds the commit rules in
# android/docs/dev/10-writing.md. It runs the checker on seeded messages, each
# rule's boundary case on both sides, and on a scratch repository through
# --range, and fails if any verdict is wrong. The checker is an opt-in
# commit-msg hook, so nothing else exercises it before a maintainer relies on
# it.
#
# Pure host: bash and git, no SDK, no build.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

CHECKER="$PROJECT_ROOT/scripts/docs/check_commit_message.sh"
[ -x "$CHECKER" ] || contract_fail "$CHECKER is missing or not executable." \
    "pre-commit runs it as a language: script hook, which needs the executable bit."

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

failures=()

pad() {
    local width="$1" text="$2"
    while [ "${#text}" -lt "$width" ]; do
        text+="x"
    done
    printf '%s' "$text"
}

# expect_pass NAME MESSAGE
expect_pass() {
    printf '%s' "$2" >"$SCRATCH/msg"
    if ! "$CHECKER" "$SCRATCH/msg" >/dev/null 2>"$SCRATCH/err"; then
        failures+=("$1: expected pass, got: $(tr '\n' ' ' <"$SCRATCH/err")")
    fi
}

# expect_fail NAME MESSAGE REASON-SUBSTRING
expect_fail() {
    printf '%s' "$2" >"$SCRATCH/msg"
    if "$CHECKER" "$SCRATCH/msg" >/dev/null 2>"$SCRATCH/err"; then
        failures+=("$1: expected a failure, the checker passed")
    elif ! grep -qF -- "$3" "$SCRATCH/err"; then
        failures+=("$1: expected '$3', got: $(tr '\n' ' ' <"$SCRATCH/err")")
    fi
}

subject72="$(pad 72 'docs(dev): ')"
subject73="$(pad 73 'docs(dev): ')"
line80="$(pad 80 'Body ')"
line81="$(pad 81 'Body ')"

expect_pass "scoped subject with a body" \
    $'docs(dev): add the seam review page\n\nSay why.\n'
expect_pass "subject alone" $'fix(sync): retry the fetch\n'
expect_pass "breaking change, no scope" $'refactor!: drop the legacy flag\n'
expect_pass "subject at 72" "${subject72}"$'\n'
expect_pass "body line at 80" $'test: pin it\n\n'"${line80}"$'\n'
expect_pass "trailing blank lines" $'ci: pin it\n\nBody.\n\n\n'
expect_pass "comment and scissors lines dropped" \
    $'ci: pin it\n\nBody.\n# '"${line81}"$'\n# ------------------------ >8 ------------------------\n'"${line81}"$'\n'
expect_pass "git-written fixup subject" \
    "fixup! $(pad 90 'docs(dev): ')"$'\n'
expect_pass "Signed-off-by trailer" \
    $'build: bump it\n\nBody.\n\nSigned-off-by: A Maintainer <a@example.org>\n'

expect_fail "subject at 73" "${subject73}"$'\n' "subject is 73 characters"
expect_fail "scope with a space" $'fix(upstream sync): retry\n' "single-token scope"
expect_fail "scope with a comma" $'fix(android,keypad): match\n' "single-token scope"
expect_fail "unknown type" $'i18n: move the toast\n' "single-token scope"
expect_fail "no type" $'Fix the thing\n' "single-token scope"
expect_fail "no space after the colon" $'fix:retry\n' "single-token scope"
expect_fail "line 2 not blank" $'fix: retry\nBody at once.\n' "line 2 is not blank"
expect_fail "body line at 81" $'test: pin it\n\n'"${line81}"$'\n' "line 3 is 81 characters"
expect_fail "co-author trailer" \
    $'feat: add it\n\nBody.\n\nCo-Authored-By: Someone <s@example.org>\n' \
    "Co-authored-by trailer"
expect_fail "lower-case co-author trailer" \
    $'feat: add it\n\nBody.\n\nco-authored-by: Someone <s@example.org>\n' \
    "Co-authored-by trailer"
expect_fail "agent e-mail in another trailer" \
    $'feat: add it\n\nBody.\n\nSigned-off-by: Agent <noreply@anthropic.com>\n' \
    "agent attribution"
expect_fail "empty message" $'# only a comment\n\n' "the message is empty"

# --range reads committed messages through git, so prove it on a scratch
# repository: one good commit, one bad, and only the bad one is named.
repo="$SCRATCH/repo"
git_scratch() {
    git -C "$repo" -c user.name=contract -c user.email=contract@example.org \
        -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"
}
git -c init.defaultBranch=main init -q "$repo"
git_scratch commit -q --allow-empty -m 'chore: start'
git_scratch commit -q --allow-empty -m 'docs(dev): say it plainly'
git_scratch commit -q --allow-empty -m 'Fix things'
if (cd "$repo" && "$CHECKER" --range HEAD~2..HEAD) >/dev/null 2>"$SCRATCH/err"; then
    failures+=("--range: expected a failure for 'Fix things', the checker passed")
else
    grep -qF 'Fix things' "$SCRATCH/err" ||
        failures+=("--range: the report does not name the bad commit")
    grep -qF 'say it plainly' "$SCRATCH/err" &&
        failures+=("--range: the report names the good commit")
    grep -qF '1 of 2 commit(s)' "$SCRATCH/err" ||
        failures+=("--range: expected '1 of 2 commit(s)' in the summary")
fi
(cd "$repo" && "$CHECKER" --range HEAD~2..HEAD~1) >/dev/null 2>&1 ||
    failures+=("--range: a range of good commits failed")

if [ "${#failures[@]}" -ne 0 ]; then
    contract_fail "check_commit_message.sh gives a wrong verdict." "${failures[@]}"
fi

contract_pass "check_commit_message.sh holds every commit rule it claims, on both sides of each boundary, for files and for --range."
