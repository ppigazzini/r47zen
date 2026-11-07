#!/bin/bash

# Contract: scripts/upstream-sync/watch_merge_request.sh runs a branch's lanes on an
# upstream merge request's merge ref only for a branch or merge commit no lane has
# seen, stops on a request that is no longer open, and every workflow the watch names
# takes the pin and shows it in its run title, which is what the watch reads back.
#
# Runs the script against stub curl, git, and gh. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

WATCH="$PROJECT_ROOT/scripts/upstream-sync/watch_merge_request.sh"
WATCH_WORKFLOW="$WORKFLOW_DIR/merge-request-watch.yml"
[ -f "$WATCH_WORKFLOW" ] || contract_fail "missing $WATCH_WORKFLOW"

BRANCH_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
MERGE_COMMIT=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
OTHER_COMMIT=cccccccccccccccccccccccccccccccccccccccc

stubs="$(mktemp -d)"
trap 'rm -rf "$stubs"' EXIT

cat >"$stubs/curl" <<'SH'
#!/bin/bash
printf '{"state":"%s"}\n' "$STUB_STATE"
SH
cat >"$stubs/git" <<'SH'
#!/bin/bash
[ -z "$STUB_MERGE_COMMIT" ] || printf '%s\trefs/merge-requests/2033/merge\n' "$STUB_MERGE_COMMIT"
SH
cat >"$stubs/gh" <<'SH'
#!/bin/bash
case "$1 $2" in
    "api repos/"*) printf '%s\n' "$STUB_BRANCH_COMMIT" ;;
    "run list") printf '%s Android CI upstream %s\n' "$STUB_LAST_HEAD" "$STUB_LAST_UPSTREAM" ;;
    "workflow run") printf '%s\n' "$*" >>"$STUB_DISPATCHES" ;;
    *) echo "unexpected gh call: $*" >&2; exit 1 ;;
esac
SH
chmod +x "$stubs/curl" "$stubs/git" "$stubs/gh"

# Print what the watch dispatched, or FAILED and its message when it stopped.
run_watch() {
    local state="$1" merge_commit="$2" last_head="$3" last_upstream="$4" output
    : >"$stubs/dispatches"
    if ! output="$(PATH="$stubs:$PATH" GITHUB_REPOSITORY=owner/repo \
        STUB_STATE="$state" STUB_MERGE_COMMIT="$merge_commit" STUB_BRANCH_COMMIT="$BRANCH_COMMIT" \
        STUB_LAST_HEAD="$last_head" STUB_LAST_UPSTREAM="$last_upstream" \
        STUB_DISPATCHES="$stubs/dispatches" \
        bash "$WATCH" 2033 port-mr-2033 one.yml two.yml 2>&1)"; then
        printf 'FAILED %s\n' "$output"
        return
    fi
    cat "$stubs/dispatches"
}

dispatched="$(run_watch opened "$MERGE_COMMIT" "$OTHER_COMMIT" "$MERGE_COMMIT")"
expected="workflow run one.yml --repo owner/repo --ref port-mr-2033 -f upstream_commit=$MERGE_COMMIT
workflow run two.yml --repo owner/repo --ref port-mr-2033 -f upstream_commit=$MERGE_COMMIT"
[ "$dispatched" = "$expected" ] || contract_fail "a new branch commit must dispatch each lane:" "$dispatched"

dispatched="$(run_watch opened "$MERGE_COMMIT" "$BRANCH_COMMIT" "$OTHER_COMMIT")"
[ "$dispatched" = "$expected" ] || contract_fail "a new merge commit must dispatch each lane:" "$dispatched"

dispatched="$(run_watch opened "$MERGE_COMMIT" "$BRANCH_COMMIT" "$MERGE_COMMIT")"
[ -z "$dispatched" ] || contract_fail "a pair a lane has seen must dispatch nothing:" "$dispatched"

dispatched="$(run_watch locked "$MERGE_COMMIT" "$OTHER_COMMIT" "$MERGE_COMMIT")"
[ "$dispatched" = "$expected" ] || contract_fail "a request locked while it merges must still dispatch:" "$dispatched"

dispatched="$(run_watch opened "" "$BRANCH_COMMIT" "$MERGE_COMMIT")"
[[ "$dispatched" == "FAILED No merge ref"* ]] || contract_fail "a missing merge ref must fail:" "$dispatched"

dispatched="$(run_watch merged "$MERGE_COMMIT" "$OTHER_COMMIT" "$MERGE_COMMIT")"
[[ "$dispatched" == "FAILED Upstream merged !2033: land port-mr-2033"* ]] ||
    contract_fail "a merged request must fail and say to land:" "$dispatched"

dispatched="$(run_watch closed "$MERGE_COMMIT" "$OTHER_COMMIT" "$MERGE_COMMIT")"
[[ "$dispatched" == "FAILED Upstream !2033 is 'closed'"* ]] ||
    contract_fail "a closed request must fail:" "$dispatched"

# Every lane the watch names must take the pin and show it in its run title.
watched="$(strip_hash_comments <"$WATCH_WORKFLOW" | grep -oE '[a-z-]+-ci\.yml')" ||
    contract_fail "merge-request-watch.yml names no lane."
for workflow in $watched; do
    body="$(strip_hash_comments <"$WORKFLOW_DIR/$workflow")"
    grep -qE '^ +upstream_commit:$' <<<"$body" ||
        contract_fail "$workflow has no upstream_commit dispatch input."
    grep -qF 'upstream_commit: ${{ inputs.upstream_commit }}' <<<"$body" ||
        contract_fail "$workflow does not pass the pin to resolve-upstream-core."
    grep -qE "^run-name: .*inputs\.upstream_commit" <<<"$body" ||
        contract_fail "$workflow does not name the pin in its run title, so the watch would rerun it every day."
done

contract_pass "the upstream merge-request watch dispatches only what moved, and each lane takes the pin."
