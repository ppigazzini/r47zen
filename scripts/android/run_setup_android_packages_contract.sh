#!/bin/bash

# Contract: every android-actions/setup-android use in the workflows and in the
# composite actions must pass an explicit `packages:` input, and that input must
# not request the legacy `tools` package. The use now lives in the
# setup-android-sdk composite action, so the scan covers both surfaces.
#
# The action's default is `packages: tools platform-tools`. The legacy `tools`
# package (SDK Tools, deprecated since the cmdline-tools split) is unpinned,
# needed by nothing in this repo, and declares `emulator` as a dependency, so
# the default silently downloads ~400 MB on every job run -- even on SDK-cache
# hits, because the action runs before the cache restore. Its download is also
# a live flake source: a corrupted sdk-tools zip ("Error reading Zip content
# from a SeekableByteChannel") failed the android-tests lane on 2026-06-10.
# The packages each job really needs are installed by its pinned "Install
# Android SDK packages" step; the action input carries only platform-tools
# (adb), which the SDK cache paths already expect. Pure host test, no SDK
# needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

violations=""

scan_files=("$WORKFLOW_DIR"/*.yml)
for action_file in "$PROJECT_ROOT"/.github/actions/*/action.yml; do
    [ -e "$action_file" ] && scan_files+=("$action_file")
done

for workflow in "${scan_files[@]}"; do
    result="$(awk -v file="$workflow" '
        /^[[:space:]]*#/ { next }
        /uses:[[:space:]]*android-actions\/setup-android/ {
            if (in_step && !found) {
                printf "%s:%d: setup-android use without an explicit packages input\n", file, uses_line
            }
            uses_line = NR
            in_step = 1
            found = 0
            next
        }
        in_step && /^[[:space:]]*-[[:space:]]/ {
            if (!found) {
                printf "%s:%d: setup-android use without an explicit packages input\n", file, uses_line
            }
            in_step = 0
        }
        in_step && /^[[:space:]]*packages:/ {
            found = 1
            if ($0 ~ /packages:.*(^|[ "\x27])tools([ "\x27]|$)/) {
                printf "%s:%d: legacy tools package requested\n", file, NR
            }
            in_step = 0
        }
        END {
            if (in_step && !found) {
                printf "%s:%d: setup-android use without an explicit packages input\n", file, uses_line
            }
        }
    ' "$workflow")"
    if [ -n "$result" ]; then
        violations="${violations}${result}"$'\n'
    fi
done

if [ -n "$violations" ]; then
    contract_fail \
        "setup-android must pass an explicit packages input without the legacy tools package:" \
        "$violations"
fi

contract_pass "every setup-android use pins its packages input and avoids the legacy tools package."
