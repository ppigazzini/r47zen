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
# reads.
#
# The composite saves the SDK cache itself, right after the install, with
# actions/cache/save: a job's own steps, which sync and build upstream code, run
# after it returns, so an entry holds only what sdkmanager installed. The plain
# actions/cache action saves at the end of the job, after that code ran, so it
# must not come back, and each save must name the paths and key of the restore
# whose miss it follows, or it writes an entry no restore reads. Pure host test,
# no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

ACTION_FILE="$PROJECT_ROOT/.github/actions/setup-android-sdk/action.yml"

[ -f "$ACTION_FILE" ] ||
    contract_fail "missing the setup-android-sdk composite action: $ACTION_FILE"

# The composite must SHA-pin its third-party actions, matching repo policy. A
# pinned line that is only a comment does not count.
live_action="$(strip_hash_comments <"$ACTION_FILE")"
grep -qE 'uses:[[:space:]]*android-actions/setup-android@[0-9a-f]{40}' <<<"$live_action" ||
    contract_fail "setup-android-sdk must SHA-pin android-actions/setup-android"
grep -qE 'uses:[[:space:]]*actions/cache/restore@[0-9a-f]{40}' <<<"$live_action" ||
    contract_fail "setup-android-sdk must restore the SDK cache with a SHA-pinned actions/cache/restore"
grep -qE 'uses:[[:space:]]*actions/cache/save@[0-9a-f]{40}' <<<"$live_action" ||
    contract_fail "setup-android-sdk must save the SDK cache with a SHA-pinned actions/cache/save"
if grep -qE 'uses:[[:space:]]*actions/cache@' <<<"$live_action"; then
    contract_fail "setup-android-sdk uses actions/cache, which saves after the job's upstream steps ran; restore and save explicitly instead"
fi

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

# Print one line per actions/cache/save step whose path block or key differs
# from the restore step its `if:` names through steps.<id>.outputs.cache-hit.
find_save_mismatches() {
    awk '
        function flush(    id, gate) {
            if (block == "") {
                return
            }
            if (block ~ /uses:[[:space:]]*actions\/cache\/restore@/ &&
                match(block, /(^|\n)[[:space:]]*id:[[:space:]]*[A-Za-z0-9_-]+/)) {
                id = substr(block, RSTART, RLENGTH)
                sub(/.*id:[[:space:]]*/, "", id)
                restore_with[id] = with_part(block)
            }
            if (block ~ /uses:[[:space:]]*actions\/cache\/save@/) {
                nsave++
                if (match(block, /steps\.[A-Za-z0-9_-]+\.outputs\.cache-hit/)) {
                    gate = substr(block, RSTART, RLENGTH)
                    sub(/^steps\./, "", gate)
                    sub(/\.outputs\.cache-hit$/, "", gate)
                    save_gate[nsave] = gate
                }
                save_name[nsave] = name
                save_with[nsave] = with_part(block)
            }
        }
        # The text from the `with:` line to the end of the step, without the
        # blank lines that separate it from the next.
        function with_part(text,    part) {
            part = substr(text, index(text, "with:"))
            sub(/[[:space:]]+$/, "", part)
            return part
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
            if (nsave == 0) {
                print "no actions/cache/save step was found"
            }
            for (i = 1; i <= nsave; i++) {
                if (!(i in save_gate)) {
                    printf "%s: not gated on a restore miss\n", save_name[i]
                } else if (!(save_gate[i] in restore_with)) {
                    printf "%s: gated on %s, which is no restore step\n", save_name[i], save_gate[i]
                } else if (save_with[i] != restore_with[save_gate[i]]) {
                    printf "%s: its path or key differs from restore step %s\n", save_name[i], save_gate[i]
                }
            }
        }
    ' "$1"
}

cat >"$fixture_dir/mirrored-save.yml" <<'EOF'
runs:
  steps:
    - name: Restore cache
      id: sdk-cache
      uses: actions/cache/restore@0000000000000000000000000000000000000000
      with:
        path: |
          sdk/ndk
        key: sdk-${{ inputs.ndk-version }}-v4
    - name: Save cache
      if: steps.sdk-cache.outputs.cache-hit != 'true'
      uses: actions/cache/save@0000000000000000000000000000000000000000
      with:
        path: |
          sdk/ndk
        key: sdk-${{ inputs.ndk-version }}-v4
EOF
# The last line is the save's key: bump it alone.
sed '$ s/-v4$/-v5/' "$fixture_dir/mirrored-save.yml" >"$fixture_dir/drifted-save.yml"
[ -z "$(find_save_mismatches "$fixture_dir/mirrored-save.yml")" ] ||
    contract_fail "the save checker flagged a save that mirrors its restore."
[ -n "$(find_save_mismatches "$fixture_dir/drifted-save.yml")" ] ||
    contract_fail "the save checker missed a save whose key drifted from its restore."

save_gaps="$(find_save_mismatches "$ACTION_FILE")"
[ -z "$save_gaps" ] ||
    contract_fail "setup-android-sdk saves a cache entry no restore reads:" "$save_gaps"

contract_pass "Android SDK setup is centralized in the setup-android-sdk composite action, each cache key covers every input its install step reads, and each save mirrors its restore inside the action."
