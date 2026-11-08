#!/bin/bash

# Contract: every workflow job that runs gradle/actions/setup-gradle restores
# and saves its own Gradle cache, on a key that hashes every tracked Gradle
# build input under android/, and nothing else caches Gradle User Home.
#
# An Actions cache entry is immutable, and a restore that hits its key exactly
# saves nothing. An input the key misses therefore keeps every later run on the
# entry saved before that input changed, and Gradle downloads the difference
# from the registries on every run, where one refused request fails the build.
# setup-gradle's own providers key on files this repo does not choose: the
# basic one, like setup-java's `cache: gradle`, hashes gradle/*.versions.toml
# at the repository root, and the catalog lives in android/gradle/. So each
# setup-gradle step must say `cache-provider: external`, no setup-java step may
# take a `cache:` input, the job's actions/cache/restore key must match every
# tracked Gradle build input through its hashFiles patterns, and its
# actions/cache/save must be gated on that restore's miss and reuse its path
# and primary key. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Tracked files Gradle reads to decide what it downloads.
GRADLE_INPUT_PATTERN='(\.gradle(\.kts)?|/gradle\.properties|\.versions\.toml|/gradle-wrapper\.properties)$'

# Print one line per violation in WORKFLOW, judged against INPUTS, the Gradle
# build inputs one per line.
find_gradle_cache_gaps() {
    awk -v inputs_text="$2" -v q="'" '
        # Translate a hashFiles glob into an anchored ERE: **/ spans zero or
        # more directories, * and ? stay inside one path segment.
        function glob_to_ere(glob,    out, i, c) {
            out = ""
            for (i = 1; i <= length(glob); i++) {
                c = substr(glob, i, 1)
                if (c == "*" && substr(glob, i + 1, 2) == "*/") {
                    out = out "(.*/)?"
                    i += 2
                } else if (c == "*" && substr(glob, i + 1, 1) == "*") {
                    out = out ".*"
                    i++
                } else if (c == "*") {
                    out = out "[^/]*"
                } else if (c == "?") {
                    out = out "[^/]"
                } else if (index(".+()|^$[]{}\\", c)) {
                    out = out "\\" c
                } else {
                    out = out c
                }
            }
            return "^" out "$"
        }
        function value_of(line) {
            sub(/^[[:space:]]*[A-Za-z_-]+:[[:space:]]*/, "", line)
            sub(/[[:space:]]+#.*$/, "", line)
            return line
        }
        function flush_step() {
            if (step_line == 0) {
                return
            }
            if (uses ~ /^gradle\/actions\/setup-gradle@/) {
                job_setup_gradle = step_line
                if (provider != "external") {
                    printf "%s:%d: setup-gradle caches Gradle User Home itself (cache-provider: %s); set cache-provider: external\n",
                        FILENAME, step_line, (provider == "" ? "unset" : provider)
                }
            }
            if (uses ~ /^actions\/setup-java@/ && java_cache != "") {
                printf "%s:%d: setup-java caches Gradle User Home on its own key (cache: %s); drop the input\n",
                    FILENAME, step_line, java_cache
            }
            if (path ~ /~\/\.gradle\//) {
                if (uses ~ /^actions\/cache\/restore@/) {
                    restore_line = step_line
                    restore_id = id
                    restore_path = path
                    restore_key = key
                } else if (uses ~ /^actions\/cache\/save@/) {
                    save_line = step_line
                    save_gate = gate
                    save_path = path
                    save_key = key
                } else {
                    printf "%s:%d: Gradle User Home cached through %s; use actions/cache/restore and actions/cache/save\n",
                        FILENAME, step_line, uses
                }
            }
            step_line = 0
        }
        function flush_job(    n, i, j, hit, rest, pattern) {
            flush_step()
            if (job != "" && job_setup_gradle) {
                if (!restore_line) {
                    printf "%s: job %s runs setup-gradle and restores no ~/.gradle cache with actions/cache/restore\n",
                        FILENAME, job
                } else {
                    n = 0
                    if (match(restore_key, /hashFiles\([^)]*\)/)) {
                        rest = substr(restore_key, RSTART, RLENGTH)
                        while (match(rest, q "[^" q "]+" q)) {
                            pattern[++n] = glob_to_ere(substr(rest, RSTART + 1, RLENGTH - 2))
                            rest = substr(rest, RSTART + RLENGTH)
                        }
                    }
                    for (i = 1; i <= ninputs; i++) {
                        hit = 0
                        for (j = 1; j <= n; j++) {
                            if (inputs[i] ~ pattern[j]) {
                                hit = 1
                            }
                        }
                        if (!hit) {
                            printf "%s:%d: job %s keys its Gradle cache on no pattern that matches %s\n",
                                FILENAME, restore_line, job, inputs[i]
                        }
                    }
                }
                if (!save_line) {
                    printf "%s: job %s saves no ~/.gradle cache with actions/cache/save\n", FILENAME, job
                } else if (restore_line) {
                    if (save_gate != restore_id) {
                        printf "%s:%d: job %s saves its Gradle cache without gating on a miss of restore step %s\n",
                            FILENAME, save_line, job, restore_id
                    }
                    if (save_path != restore_path) {
                        printf "%s:%d: job %s saves a Gradle cache path that differs from its restore\n",
                            FILENAME, save_line, job
                    }
                    if (save_key != "${{ steps." restore_id ".outputs.cache-primary-key }}") {
                        printf "%s:%d: job %s saves its Gradle cache under a key other than its restore primary key\n",
                            FILENAME, save_line, job
                    }
                }
            }
            job_setup_gradle = restore_line = save_line = 0
            restore_id = restore_path = restore_key = ""
            save_gate = save_path = save_key = ""
        }
        BEGIN {
            ninputs = split(inputs_text, inputs, "\n")
        }
        /^[[:space:]]*#/ {
            next
        }
        /^jobs:/ {
            in_jobs = 1
            next
        }
        !in_jobs {
            next
        }
        /^  [A-Za-z0-9_-]+:/ {
            flush_job()
            job = $1
            sub(/:$/, "", job)
            next
        }
        /^      - / {
            flush_step()
            step_line = FNR
            uses = id = provider = java_cache = key = path = gate = ""
            path_indent = -1
        }
        step_line == 0 {
            next
        }
        {
            indent = match($0, /[^ ]/) - 1
            if (path_indent >= 0) {
                if (indent > path_indent) {
                    path = path "\n" substr($0, indent + 1)
                    next
                }
                path_indent = -1
            }
            line = $0
            sub(/^      - /, "        ", line)
            if (line ~ /^[[:space:]]*uses:/) {
                uses = value_of(line)
            } else if (line ~ /^[[:space:]]*id:/) {
                id = value_of(line)
            } else if (line ~ /^[[:space:]]*cache-provider:/) {
                provider = value_of(line)
            } else if (line ~ /^[[:space:]]*cache:/) {
                java_cache = value_of(line)
            } else if (line ~ /^[[:space:]]*key:/) {
                key = value_of(line)
            } else if (line ~ /^[[:space:]]*path:/) {
                path = value_of(line)
                path_indent = indent
            }
            if (match(line, /steps\.[A-Za-z0-9_-]+\.outputs\.cache-hit/)) {
                gate = substr(line, RSTART, RLENGTH)
                sub(/^steps\./, "", gate)
                sub(/\.outputs\.cache-hit$/, "", gate)
            }
        }
        END {
            flush_job()
        }
    ' "$1"
}

# Prove the checker on a seeded workflow first: the complete one passes, and
# each seeded gap fails it.
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
fixture_inputs="android/build.gradle
android/app/build.gradle
android/settings.gradle
android/gradle.properties
android/gradle/libs.versions.toml
android/gradle/wrapper/gradle-wrapper.properties"
cat >"$fixture_dir/complete.yml" <<'EOF'
jobs:
  build:
    runs-on: ubuntu-26.04
    steps:
      - name: Restore Gradle cache
        id: gradle-cache
        uses: actions/cache/restore@0000000000000000000000000000000000000000 # v6.1.0
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('android/**/*.gradle', 'android/gradle.properties', 'android/gradle/*.versions.toml', 'android/gradle/wrapper/gradle-wrapper.properties') }}

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@0000000000000000000000000000000000000000 # v6.4.1
        with:
          cache-provider: external

      - name: Build
        run: cd android && ./gradlew assembleDebug

      - name: Save Gradle cache
        if: steps.gradle-cache.outputs.cache-hit != 'true'
        uses: actions/cache/save@0000000000000000000000000000000000000000 # v6.1.0
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ steps.gradle-cache.outputs.cache-primary-key }}
EOF
sed 's/cache-provider: external/cache-provider: basic/' \
    "$fixture_dir/complete.yml" >"$fixture_dir/basic-provider.yml"
sed "s#, 'android/gradle/\*\.versions\.toml'##" \
    "$fixture_dir/complete.yml" >"$fixture_dir/catalog-unkeyed.yml"
sed 's/outputs\.cache-primary-key/outputs.cache-matched-key/' \
    "$fixture_dir/complete.yml" >"$fixture_dir/drifted-save.yml"

complete_report="$(find_gradle_cache_gaps "$fixture_dir/complete.yml" "$fixture_inputs")"
[ -z "$complete_report" ] ||
    contract_fail "the Gradle cache checker flagged the complete fixture:" "$complete_report"
for seeded in basic-provider catalog-unkeyed drifted-save; do
    [ -n "$(find_gradle_cache_gaps "$fixture_dir/$seeded.yml" "$fixture_inputs")" ] ||
        contract_fail "the Gradle cache checker missed the seeded gap in $seeded.yml."
done

workflow_uses 'uses:[[:space:]]*gradle/actions/setup-gradle@' ||
    contract_fail "no workflow runs gradle/actions/setup-gradle, so this contract checks nothing; retire it or fix its pattern"

tracked="$(git -C "$PROJECT_ROOT" ls-files -- android)"
gradle_inputs="$(grep -E "$GRADLE_INPUT_PATTERN" <<<"$tracked" || true)"
[ -n "$gradle_inputs" ] ||
    contract_fail "git lists no tracked Gradle build input under android/"

violations=""
for workflow in "$WORKFLOW_DIR"/*.yml; do
    result="$(find_gradle_cache_gaps "$workflow" "$gradle_inputs")"
    if [ -n "$result" ]; then
        violations="${violations}${result}"$'\n'
    fi
done

if [ -n "$violations" ]; then
    contract_fail \
        "a Gradle cache key misses a Gradle build input, or a job caches Gradle User Home another way:" \
        "$violations"
fi

contract_pass "every setup-gradle job restores and saves its own Gradle cache on a key that hashes every tracked Gradle build input."
