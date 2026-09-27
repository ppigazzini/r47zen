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
# routes SDK setup through the composite, when the composite stops SHA-pinning
# its third-party actions, or when a cache key omits an input its install step
# reads. Pure host test, no SDK needed.

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

# Every input an install step reads must appear in the key of the cache that
# gates it. The install is skipped on a cache hit, so an input missing from the
# key (compile-sdk-minor, say) lets a bump reuse the old packages and install
# nothing. Each install step names its gating cache through
# steps.<id>.outputs.cache-hit; the input sets compare as exact tokens, so
# inputs.compile-sdk never stands in for inputs.compile-sdk-minor.
find_cache_key_gaps() {
    awk '
        function flush(    n, i, names) {
            if (block == "") {
                return
            }
            if (match(block, /(^|\n)[[:space:]]*id:[[:space:]]*[A-Za-z0-9_-]+/)) {
                id = substr(block, RSTART, RLENGTH)
                sub(/.*id:[[:space:]]*/, "", id)
                if (match(block, /(^|\n)[[:space:]]*key:[^\n]*/)) {
                    key_of[id] = substr(block, RSTART, RLENGTH)
                }
            }
            if (match(block, /steps\.[A-Za-z0-9_-]+\.outputs\.cache-hit/)) {
                gate = substr(block, RSTART, RLENGTH)
                sub(/^steps\./, "", gate)
                sub(/\.outputs\.cache-hit$/, "", gate)
                ninstall++
                install_gate[ninstall] = gate
                install_name[ninstall] = name
                install_inputs[ninstall] = ""
                rest = block
                while (match(rest, /inputs\.[a-z0-9-]+/)) {
                    token = substr(rest, RSTART + 7, RLENGTH - 7)
                    if (token != "include-emulator") {
                        install_inputs[ninstall] = install_inputs[ninstall] " " token
                    }
                    rest = substr(rest, RSTART + RLENGTH)
                }
            }
        }
        /^    - name:/ {
            flush()
            block = ""
            name = $0
            sub(/^    - name:[[:space:]]*/, "", name)
        }
        { block = block $0 "\n" }
        END {
            flush()
            if (ninstall == 0) {
                print "no install step gated on a cache hit was found"
            }
            for (i = 1; i <= ninstall; i++) {
                gate = install_gate[i]
                if (!(gate in key_of)) {
                    printf "%s: gating cache step %s has no key\n", install_name[i], gate
                    continue
                }
                split("", in_key)
                rest = key_of[gate]
                while (match(rest, /inputs\.[a-z0-9-]+/)) {
                    in_key[substr(rest, RSTART + 7, RLENGTH - 7)] = 1
                    rest = substr(rest, RSTART + RLENGTH)
                }
                n = split(install_inputs[i], names, " ")
                for (j = 1; j <= n; j++) {
                    if (!(names[j] in in_key)) {
                        printf "%s reads inputs.%s, which the %s cache key omits\n", install_name[i], names[j], gate
                    }
                }
            }
        }
    ' "$1"
}

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
cat >"$fixture_dir/stale-key.yml" <<'EOF'
runs:
  steps:
    - name: Restore cache
      id: sdk-cache
      uses: actions/cache@0000000000000000000000000000000000000000
      with:
        key: ${{ runner.os }}-sdk-${{ inputs.compile-sdk }}-v1
    - name: Install packages
      if: steps.sdk-cache.outputs.cache-hit != 'true'
      env:
        COMPILE_SDK: ${{ inputs.compile-sdk }}
        COMPILE_SDK_MINOR: ${{ inputs.compile-sdk-minor }}
      run: sdkmanager "platforms;android-${COMPILE_SDK}.${COMPILE_SDK_MINOR}"
EOF
sed 's/-sdk-\${{ inputs.compile-sdk }}-v1/-sdk-${{ inputs.compile-sdk }}-${{ inputs.compile-sdk-minor }}-v1/' \
    "$fixture_dir/stale-key.yml" >"$fixture_dir/full-key.yml"

[ -n "$(find_cache_key_gaps "$fixture_dir/stale-key.yml")" ] ||
    contract_fail "cache-key checker missed an input the seeded fixture leaves out of its key."
full_report="$(find_cache_key_gaps "$fixture_dir/full-key.yml")"
[ -z "$full_report" ] ||
    contract_fail "cache-key checker flagged the complete fixture:" "$full_report"

key_gaps="$(find_cache_key_gaps "$ACTION_FILE")"
if [ -n "$key_gaps" ]; then
    contract_fail "setup-android-sdk installs from an input its cache key omits, so a bump of it would hit a stale cache:" "$key_gaps"
fi

contract_pass "Android SDK setup is centralized in the setup-android-sdk composite action, and each cache key covers every input its install step reads."
