#!/bin/bash

# Resolver-policy test harness for scripts/upstream-sync/upstream.sh (REPORT-24
# roadmap U2). It asserts the resolution contract that REPORT-24 Sec.25 restored,
# so the policy is verified behaviour instead of prose that can silently drift:
#
#   * no source pin and no lock  => --auto and --latest resolve the latest HEAD
#   * a lock pin                  => honoured by --auto and --locked, while
#                                    --latest still ignores it and follows HEAD
#   * no pin anywhere            => --locked fails loudly (non-zero exit)
#   * a pin in upstream.source   => verify-source-policy fails; absent => passes
#
# The network is stubbed by pointing the upstream URL at a throwaway local Git
# repository, so `git ls-remote` resolves a deterministic commit offline and the
# test runs in any lane. The synthetic upstream.source/upstream.lock files are
# injected through R47_UPSTREAM_SOURCE_FILE/R47_UPSTREAM_LOCK_FILE so the real
# tracked files are never touched. Everything lives under one temp dir that is
# always removed, including on error.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPSTREAM_SH="$SCRIPT_DIR/upstream.sh"

WORK_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

UPSTREAM_REPO="$WORK_DIR/upstream-repo"
SRC_FILE="$WORK_DIR/upstream.source"
LOCK_FILE="$WORK_DIR/upstream.lock"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    PASS_COUNT=$((PASS_COUNT + 1))
    printf '  ok   %s\n' "$1"
}

fail() {
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf '  FAIL %s\n' "$1" >&2
}

assert_eq() {
    local label="$1"
    local expected="$2"
    local actual="$3"

    if [ "$expected" = "$actual" ]; then
        pass "$label"
    else
        fail "$label (expected '$expected', got '$actual')"
    fi
}

# Build a local Git repo to stand in for the upstream remote, with an older
# commit COMMIT_OLD and a newer HEAD COMMIT_HEAD so a stale lock pin is provably
# distinct from the latest commit.
git init -q -b main "$UPSTREAM_REPO"
git -C "$UPSTREAM_REPO" -c user.email=ci@example.com -c user.name=ci \
    commit -q --allow-empty -m "upstream commit old"
COMMIT_OLD="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"
git -C "$UPSTREAM_REPO" -c user.email=ci@example.com -c user.name=ci \
    commit -q --allow-empty -m "upstream commit head"
COMMIT_HEAD="$(git -C "$UPSTREAM_REPO" rev-parse HEAD)"

write_source() {
    # write_source [<pinned-commit>]; omit the argument for an unpinned source.
    {
        printf 'upstream_url=%s\n' "$UPSTREAM_REPO"
        printf 'upstream_ref=HEAD\n'
        if [ "$#" -ge 1 ]; then
            printf 'upstream_commit=%s\n' "$1"
        fi
    } >"$SRC_FILE"
}

write_lock() {
    # write_lock <pinned-commit>
    {
        printf 'upstream_url=%s\n' "$UPSTREAM_REPO"
        printf 'upstream_ref=HEAD\n'
        printf 'upstream_commit=%s\n' "$1"
    } >"$LOCK_FILE"
}

resolve_field() {
    # resolve_field <mode-flag> <R47_RESOLVED_* field>; echoes the resolved value.
    local mode="$1"
    local field="$2"
    local out=""
    out="$(R47_UPSTREAM_SOURCE_FILE="$SRC_FILE" R47_UPSTREAM_LOCK_FILE="$LOCK_FILE" \
        bash "$UPSTREAM_SH" resolve "$mode" --format shell)"
    (
        eval "$out"
        printf '%s\n' "${!field}"
    )
}

resolve_expecting_failure() {
    # resolve_expecting_failure <mode-flag>; returns 0 when the resolve fails.
    if R47_UPSTREAM_SOURCE_FILE="$SRC_FILE" R47_UPSTREAM_LOCK_FILE="$LOCK_FILE" \
        bash "$UPSTREAM_SH" resolve "$1" --format none >/dev/null 2>&1; then
        return 1
    fi
    return 0
}

verify_source_policy_status() {
    if R47_UPSTREAM_SOURCE_FILE="$SRC_FILE" R47_UPSTREAM_LOCK_FILE="$LOCK_FILE" \
        bash "$UPSTREAM_SH" verify-source-policy >/dev/null 2>&1; then
        printf 'pass\n'
    else
        printf 'fail\n'
    fi
}

echo "Scenario 1: no source pin, no lock => auto and latest follow HEAD"
write_source
rm -f "$LOCK_FILE"
assert_eq "auto resolves HEAD" "$COMMIT_HEAD" "$(resolve_field --auto R47_RESOLVED_UPSTREAM_COMMIT)"
assert_eq "auto mode is latest" "latest" "$(resolve_field --auto R47_RESOLVED_UPSTREAM_MODE)"
assert_eq "latest resolves HEAD" "$COMMIT_HEAD" "$(resolve_field --latest R47_RESOLVED_UPSTREAM_COMMIT)"

echo "Scenario 2: lock pins the old commit while HEAD has advanced"
write_source
write_lock "$COMMIT_OLD"
assert_eq "auto honours the lock pin" "$COMMIT_OLD" "$(resolve_field --auto R47_RESOLVED_UPSTREAM_COMMIT)"
assert_eq "auto mode is locked" "locked" "$(resolve_field --auto R47_RESOLVED_UPSTREAM_MODE)"
assert_eq "locked honours the lock pin" "$COMMIT_OLD" "$(resolve_field --locked R47_RESOLVED_UPSTREAM_COMMIT)"
assert_eq "latest ignores the lock pin" "$COMMIT_HEAD" "$(resolve_field --latest R47_RESOLVED_UPSTREAM_COMMIT)"

echo "Scenario 3: no pin anywhere => locked fails loudly"
write_source
rm -f "$LOCK_FILE"
if resolve_expecting_failure --locked; then
    pass "locked without any pin exits non-zero"
else
    fail "locked without any pin should exit non-zero"
fi

echo "Scenario 4: verify-source-policy guards the tracked source"
write_source
assert_eq "unpinned source passes the guard" "pass" "$(verify_source_policy_status)"
write_source "$COMMIT_OLD"
assert_eq "pinned source fails the guard" "fail" "$(verify_source_policy_status)"

echo
printf 'Resolver policy: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
