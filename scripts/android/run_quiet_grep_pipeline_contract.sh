#!/bin/bash

# Contract: no repo-owned shell script, workflow, or composite action pipes a
# command into a quiet grep (grep -q, --quiet, --silent). grep -q exits at its
# first match; the command feeding it then dies of SIGPIPE on its next write,
# and under pipefail the pipeline reports that death instead of the match. The
# outcome depends on how much the producer still has to write, not on the
# input: `unzip -Z1 bundle.aab | grep -Eq '^META-INF/...SF$'` reported "no
# signature" on every run against a signed release bundle, because jarsigner
# writes the signature entries first. Capture the output, then match it:
#
#   entries="$(unzip -Z1 "$bundle")"
#   grep -Eq '^META-INF/[^/]+\.SF$' <<<"$entries"
#
# A pipe at the end of one line into a quiet grep on the next counts too. The
# checker first proves it flags every seeded pipeline and passes the clean
# fixture, then scans the real tree, less this file, whose fixtures are the
# pattern. Pure host test, no SDK needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Print "file:line: text" for each pipe into a quiet grep in the given files.
find_quiet_grep_pipelines() {
    awk '
        FNR == 1 { carried = 0 }
        /^[[:space:]]*#/ { next }
        {
            line = $0
            # A pipe that ends the previous line feeds the first command here.
            if (carried) {
                line = "| " line
            }
            if (line ~ /(^|[^|])\|[[:space:]]*grep([[:space:]]+-[^[:space:]]+)*[[:space:]]+(-[A-Za-z]*q[A-Za-z]*|--quiet|--silent)([[:space:]]|$)/) {
                printf "%s:%d: %s\n", FILENAME, FNR, $0
            }
            carried = ($0 ~ /(^|[^|])\|[[:space:]]*$/)
        }
    ' "$@"
}

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

cat >"$fixture_dir/same-line.sh" <<'EOF'
if unzip -Z1 "$bundle" | grep -Eq '^META-INF/'; then echo signed; fi
EOF
cat >"$fixture_dir/split-options.sh" <<'EOF'
dpkg -L pkg | grep -F -q profile.a || exit 1
EOF
cat >"$fixture_dir/long-option.yml" <<'EOF'
      run: |
        git remote | grep --quiet --line-regexp upstream
EOF
cat >"$fixture_dir/continued.sh" <<'EOF'
sdkmanager --list 2>/dev/null |
    grep -Fxq 'platforms;android-37.0'
EOF
cat >"$fixture_dir/clean.sh" <<'EOF'
# entries | grep -q is fine in a comment
entries="$(unzip -Z1 "$bundle")"
grep -Eq '^META-INF/' <<<"$entries"
grep -q needle haystack.txt
test -f a || grep -q needle haystack.txt
sed -n p file | grep -v '^#' | sort
EOF

for fixture in same-line.sh split-options.sh long-option.yml continued.sh; do
    report="$(find_quiet_grep_pipelines "$fixture_dir/$fixture")"
    [ -n "$report" ] ||
        contract_fail "quiet-grep checker missed the seeded pipeline in fixture $fixture."
done
clean_report="$(find_quiet_grep_pipelines "$fixture_dir/clean.sh")"
[ -z "$clean_report" ] ||
    contract_fail "quiet-grep checker flagged the clean fixture:" "$clean_report"

self="scripts/android/$(basename "${BASH_SOURCE[0]}")"
mapfile -t files < <(
    git -C "$PROJECT_ROOT" ls-files -- 'scripts/*.sh' '.github/workflows/*.yml' '.github/actions/*/action.yml' |
        grep -vxF -- "$self"
)
[ "${#files[@]}" -gt 0 ] || contract_fail "no tracked scripts, workflows, or composite actions found."

violations="$(cd "$PROJECT_ROOT" && find_quiet_grep_pipelines "${files[@]}")"
if [ -n "$violations" ]; then
    contract_fail \
        "a pipeline ends in a quiet grep; capture the producer's output and match it with <<< instead:" \
        "$violations"
fi

contract_pass "no pipeline ends in a quiet grep (${#files[@]} files scanned)."
