#!/bin/bash

# Regression guard for the retry_with_backoff network-resilience helper in
# scripts/lib/common.sh. The upstream sync and submodule hydration lanes wrap
# every git ls-remote / git fetch in this helper so a single transient transport
# error ("early EOF", "fetch-pack: invalid index-pack output") retries instead
# of aborting a whole release. This asserts the retry contract directly, with no
# network: a flaky command is simulated through a counter file that fails a
# fixed number of times before succeeding.
#
# R47_NET_RETRY_DELAY=0 keeps the backoff sleeps instant so the suite stays fast.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

WORK_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

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

# A command that fails its first N invocations, then succeeds. The invocation
# count survives across calls through a counter file so retry_with_backoff sees
# a genuinely stateful flaky command. Echoes its attempt count to stdout so the
# test can also assert the helper does not swallow command output.
COUNTER="$WORK_DIR/counter"
flaky() {
    # flaky <fail-until>: fail while the running count is below <fail-until>.
    local fail_until="$1"
    local count=0
    [ -f "$COUNTER" ] && count=$(cat "$COUNTER")
    count=$((count + 1))
    printf '%s\n' "$count" >"$COUNTER"
    printf 'attempt-%s\n' "$count"
    [ "$count" -ge "$fail_until" ]
}

export R47_NET_RETRY_DELAY=0

echo "Scenario 1: a command that succeeds first try runs exactly once"
rm -f "$COUNTER"
out="$(retry_with_backoff "first-try" flaky 1)"
assert_eq "returns success" "0" "$?"
assert_eq "ran exactly once" "1" "$(cat "$COUNTER")"
assert_eq "passes command stdout through" "attempt-1" "$out"

echo "Scenario 2: transient failures are retried until success"
rm -f "$COUNTER"
out="$(R47_NET_RETRY_ATTEMPTS=3 retry_with_backoff "flaky-twice" flaky 3)"
assert_eq "returns success after retries" "0" "$?"
assert_eq "ran three times" "3" "$(cat "$COUNTER")"
assert_eq "final stdout is the successful attempt" "attempt-3" "$out"

echo "Scenario 3: exhausting the attempt budget surfaces the failure"
rm -f "$COUNTER"
status=0
R47_NET_RETRY_ATTEMPTS=2 retry_with_backoff "always-fails" flaky 99 >/dev/null 2>&1 || status=$?
assert_eq "returns non-zero after the budget is spent" "1" "$status"
assert_eq "stopped after the configured attempts" "2" "$(cat "$COUNTER")"

echo "Scenario 4: R47_NET_RETRY_ATTEMPTS=1 disables retry"
rm -f "$COUNTER"
status=0
R47_NET_RETRY_ATTEMPTS=1 retry_with_backoff "no-retry" flaky 99 >/dev/null 2>&1 || status=$?
assert_eq "returns non-zero" "1" "$status"
assert_eq "ran exactly once" "1" "$(cat "$COUNTER")"

echo "Scenario 5: a non-numeric attempts override falls back to the default of 3"
rm -f "$COUNTER"
out="$(R47_NET_RETRY_ATTEMPTS=bogus retry_with_backoff "bad-attempts" flaky 3)"
assert_eq "default budget still allows three attempts" "3" "$(cat "$COUNTER")"

echo
printf 'Fetch retry: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
if [ "$FAIL_COUNT" -ne 0 ]; then
    exit 1
fi
