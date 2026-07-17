#!/bin/bash

# Contract: the Android SDK provisioning sequence (select a writable SDK root,
# run setup-android for adb, accept licenses, resolve and cache the package
# paths, install the pinned packages) lives in exactly one place -- the
# .github/actions/setup-android-sdk composite action -- and is never re-inlined
# into a workflow job.
#
# That composite is the single home for the SDK setup across android-ci.yml and
# the protected android-release.yml. A reappearing inline
# block is drift that re-opens the duplication, so this guard fails when the
# characteristic inline step names show up in a workflow again, when no workflow
# routes SDK setup through the composite, or when the composite stops SHA-pinning
# its third-party actions. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

ACTION_FILE="$PROJECT_ROOT/.github/actions/setup-android-sdk/action.yml"

[ -f "$ACTION_FILE" ] ||
    contract_fail "missing the setup-android-sdk composite action: $ACTION_FILE"

# The composite must SHA-pin its third-party actions, matching repo policy.
grep -qE 'uses:[[:space:]]*android-actions/setup-android@[0-9a-f]{40}' "$ACTION_FILE" ||
    contract_fail "setup-android-sdk must SHA-pin android-actions/setup-android"
grep -qE 'uses:[[:space:]]*actions/cache@[0-9a-f]{40}' "$ACTION_FILE" ||
    contract_fail "setup-android-sdk must SHA-pin actions/cache"

# A workflow job must provision the SDK through the composite, never inline.
workflow_uses 'uses:[[:space:]]*\./\.github/actions/setup-android-sdk' ||
    contract_fail "no workflow uses the ./.github/actions/setup-android-sdk composite action"

# These step names characterize a hand-rolled inline SDK block. They now live
# only inside the composite (which is not under the workflow dir), so any
# reappearance in a workflow is re-inlined drift.
inline=""
for marker in \
    "Accept Android SDK licenses non-interactively" \
    "Resolve Android SDK cache paths"; do
    hits="$(workflow_grep "name:[[:space:]]*${marker}" || true)"
    if [ -n "$hits" ]; then
        inline="${inline}${hits}"$'\n'
    fi
done

if [ -n "$inline" ]; then
    contract_fail \
        "Android SDK setup is re-inlined in a workflow; route it through the composite:" \
        "$inline"
fi

contract_pass "Android SDK setup is centralized in the setup-android-sdk composite action."
