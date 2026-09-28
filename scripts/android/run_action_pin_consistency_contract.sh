#!/bin/bash

# Contract: anything pinned in more than one place is pinned to one version
# everywhere, and no job runs on a runner label that moves by itself.
#
# - Every third-party `uses:` under .github/ (workflows and composite actions)
#   names a full commit SHA and a `# <tag>` comment, and every step that uses
#   the same owner/repo (actions/cache and actions/cache/restore alike) names
#   the same SHA and the same tag. A bump that edits one file and misses
#   another leaves two versions of one action running, and nothing fails.
# - The tools Shell Lint downloads are the tools pre-commit runs locally:
#   SHFMT_VERSION matches the scop/pre-commit-shfmt rev and SHELLCHECK_VERSION
#   the shellcheck-py rev, each defined exactly once. ruff's pre-commit rev
#   matches the ruff uv.lock resolves for the contract suite. Every
#   setup-python python-version matches pyproject.toml's requires-python
#   floor, ruff target-version, and ty python-version.
# - Every runner label names a versioned image. A -latest label moves to a new
#   OS on GitHub's schedule with no commit here; that move belongs in a commit
#   with a CI run as its evidence.
#
# Each check first proves it fails on seeded fixtures, then reads the real
# files. Pure host, no network.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

# Print one line per third-party `uses:` under DIR that is not SHA-pinned with
# a tag comment, or whose owner/repo another step pins differently.
action_pin_gaps() {
    local dir="$1" file line text spec ref tag repo pin
    local uses_re='uses:[[:space:]]*([^[:space:]]+)([[:space:]]+#[[:space:]]*([^[:space:]]+))?'
    local -A pin_of=() where_of=()
    while IFS=: read -r file line text; do
        text="${text//[\"\']/}"
        [[ "$text" =~ $uses_re ]] || continue
        spec="${BASH_REMATCH[1]}"
        tag="${BASH_REMATCH[3]}"
        case "$spec" in ./* | docker://*) continue ;; esac
        file="${file#"$dir/"}"
        ref="${spec##*@}"
        repo="$(cut -d/ -f1-2 <<<"${spec%@*}")"
        if [[ "$spec" != *@* || ! "$ref" =~ ^[0-9a-f]{40}$ ]]; then
            echo "$file:$line: $spec is not pinned to a full commit SHA"
            continue
        fi
        if [ -z "$tag" ]; then
            echo "$file:$line: $spec has no '# <tag>' comment"
            continue
        fi
        pin="$ref # $tag"
        if [ -z "${pin_of[$repo]+set}" ]; then
            pin_of[$repo]="$pin"
            where_of[$repo]="$file:$line"
        elif [ "${pin_of[$repo]}" != "$pin" ]; then
            echo "$file:$line: $repo is $pin, but ${where_of[$repo]} pins ${pin_of[$repo]}"
        fi
    done < <(grep -rnE --include='*.yml' --include='*.yaml' \
        '^[[:space:]]*(-[[:space:]]+)?uses:' "$dir" 2>/dev/null | LC_ALL=C sort -t: -k1,1 -k2,2n)
}

# Print each live line under DIR that names a floating runner label.
runner_label_gaps() {
    grep -rnE --include='*.yml' --include='*.yaml' \
        '(ubuntu|windows|macos)-latest' "$1" 2>/dev/null |
        grep -vE ':[0-9]+:[[:space:]]*#' | sed "s#^$1/##" || true
}

# Print the rev of the pre-commit repo whose URL ends in /REPO, from CONFIG.
precommit_rev() {
    awk -v repo="/$2" '
        /^[[:space:]]*-[[:space:]]*repo:/ {
            hit = substr($NF, length($NF) - length(repo) + 1) == repo
            next
        }
        hit && /^[[:space:]]*rev:/ { print $2; exit }
    ' "$1"
}

# Print the value of the env key NAME in FILE; fail unless it is defined once.
single_env_value() {
    local file="$1" name="$2" values
    values="$(sed -n "s/^[[:space:]]*${name}:[[:space:]]*//p" "$file" | tr -d "\"'")"
    [ "$(grep -c . <<<"$values")" = 1 ] || return 1
    printf '%s' "$values"
}

# Print one line per tool pinned differently in the Shell Lint workflow, the
# pre-commit config, uv.lock, pyproject.toml, and the workflows under DIR.
tool_pin_gaps() {
    local shell_lint="$1" precommit="$2" uv_lock="$3" pyproject="$4" dir="$5"
    local lane hook

    lane="$(single_env_value "$shell_lint" SHFMT_VERSION)" ||
        echo "SHFMT_VERSION is not defined exactly once in ${shell_lint##*/}"
    hook="$(precommit_rev "$precommit" scop/pre-commit-shfmt)"
    hook="${hook%%-*}"
    [ -n "$lane" ] && [ "$lane" = "$hook" ] ||
        echo "shfmt: Shell Lint pins '$lane', the pre-commit hook '$hook'"

    lane="$(single_env_value "$shell_lint" SHELLCHECK_VERSION)" ||
        echo "SHELLCHECK_VERSION is not defined exactly once in ${shell_lint##*/}"
    hook="$(precommit_rev "$precommit" shellcheck-py/shellcheck-py)"
    hook="$(cut -d. -f1-3 <<<"${hook%%-*}")"
    [ -n "$lane" ] && [ "$lane" = "$hook" ] ||
        echo "shellcheck: Shell Lint pins '$lane', the pre-commit hook '$hook'"

    lane="$(awk '/^name = "ruff"$/ { getline; gsub(/"/, "", $3); print $3; exit }' "$uv_lock")"
    hook="$(precommit_rev "$precommit" astral-sh/ruff-pre-commit)"
    hook="${hook#v}"
    [ -n "$lane" ] && [ "$lane" = "$hook" ] ||
        echo "ruff: uv.lock resolves '$lane', the pre-commit hook '$hook'"

    local floor target ty_python setup_python
    floor="$(sed -n 's/^requires-python = ">=\([0-9.]*\)".*/\1/p' "$pyproject")"
    target="$(sed -n 's/^target-version = "py3\([0-9]*\)"/3.\1/p' "$pyproject")"
    ty_python="$(sed -n 's/^python-version = "\([0-9.]*\)"/\1/p' "$pyproject")"
    [ -n "$floor" ] && [ "$floor" = "$target" ] && [ "$floor" = "$ty_python" ] ||
        echo "python: requires-python '$floor', ruff target '$target', ty '$ty_python'"
    while IFS= read -r setup_python; do
        [ "${setup_python##*:}" = "$floor" ] ||
            echo "python: ${setup_python%:*} sets up '${setup_python##*:}', pyproject.toml requires '$floor'"
    done < <(grep -rnE --include='*.yml' --include='*.yaml' \
        '^[[:space:]]*python-version:' "$dir" 2>/dev/null |
        sed -E "s#^$dir/##; s/^([^:]*:[0-9]+):[[:space:]]*python-version:[[:space:]]*[\"']?([^\"'[:space:]]*).*/\1:\2/")
}

fixtures="$(mktemp -d)"
trap 'rm -rf "$fixtures"' EXIT

# --- seeded fixtures -----------------------------------------------------------
good="$fixtures/good"
mkdir -p "$good/.github/workflows" "$good/.github/actions/x"
cat >"$good/.github/workflows/shell-lint.yml" <<'YML'
env:
  SHELLCHECK_VERSION: v0.11.0
jobs:
  a:
    runs-on: ubuntu-24.04
    env:
      SHFMT_VERSION: v3.14.1
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
      # - uses: actions/checkout@0000000000000000000000000000000000000000 # v1
      # runs-on: ubuntu-latest
      - uses: ./.github/actions/x
      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
        with:
          python-version: '3.14'
YML
cat >"$good/.github/actions/x/action.yml" <<'YML'
runs:
  using: composite
  steps:
    - uses: "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1" # v7.0.1
    - uses: actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
YML
cat >"$good/.pre-commit-config.yaml" <<'YML'
repos:
  - repo: https://github.com/astral-sh/ruff-pre-commit
    rev: v0.16.9
  - repo: https://github.com/shellcheck-py/shellcheck-py
    rev: v0.11.0.1-1
  - repo: https://github.com/scop/pre-commit-shfmt
    rev: v3.14.1-1
YML
cat >"$good/uv.lock" <<'TOML'
[[package]]
name = "ruff"
version = "0.16.9"
TOML
cat >"$good/pyproject.toml" <<'TOML'
requires-python = ">=3.14"
target-version = "py314"
python-version = "3.14"
TOML

# Run all three checks against fixture tree ROOT and print every gap.
fixture_gaps() {
    local root="$1"
    action_pin_gaps "$root/.github"
    runner_label_gaps "$root/.github"
    tool_pin_gaps "$root/.github/workflows/shell-lint.yml" "$root/.pre-commit-config.yaml" \
        "$root/uv.lock" "$root/pyproject.toml" "$root/.github"
}

gaps="$(fixture_gaps "$good")"
[ -z "$gaps" ] || contract_fail "the checker flagged the consistent fixture:" "$gaps"

# seed NAME FILE SED_EXPR: copy the good tree to NAME with one edit to FILE.
seed() {
    cp -R "$good" "$fixtures/$1"
    sed -i -e "$3" "$fixtures/$1/$2"
}
seed split-sha .github/actions/x/action.yml 's/3d3c42e5aac5ba805825da76410c181273ba90b1/1111111111111111111111111111111111111111/'
seed split-tag .github/actions/x/action.yml 's/v6\.1\.0/v6.1.1/'
seed tag-ref .github/workflows/shell-lint.yml 's/actions\/cache@[0-9a-f]* # v6.1.0/actions\/cache@v6/'
seed no-comment .github/workflows/shell-lint.yml 's/ # v7.0.0$//'
seed latest-runner .github/workflows/shell-lint.yml 's/runs-on: ubuntu-24.04/runs-on: ubuntu-latest/'
seed shfmt-split .pre-commit-config.yaml 's/v3.14.1-1/v3.15.0-1/'
seed shfmt-twice .github/workflows/shell-lint.yml 's/^env:$/env:\n  SHFMT_VERSION: v3.14.1/'
seed shellcheck-split .github/workflows/shell-lint.yml 's/SHELLCHECK_VERSION: v0.11.0/SHELLCHECK_VERSION: v0.10.0/'
seed ruff-split uv.lock 's/0.16.9/0.16.10/'
seed python-floor pyproject.toml 's/>=3.14/>=3.15/'
seed python-setup .github/workflows/shell-lint.yml "s/'3.14'/'3.13'/"
for fixture in split-sha split-tag tag-ref no-comment latest-runner shfmt-split \
    shfmt-twice shellcheck-split ruff-split python-floor python-setup; do
    [ -n "$(fixture_gaps "$fixtures/$fixture")" ] ||
        contract_fail "the checker missed the seeded gap in fixture $fixture."
done

# --- the real tree -------------------------------------------------------------
gaps="$(action_pin_gaps "$PROJECT_ROOT/.github")"
[ -z "$gaps" ] || contract_fail "an action is pinned inconsistently:" "$gaps"

gaps="$(runner_label_gaps "$PROJECT_ROOT/.github")"
[ -z "$gaps" ] || contract_fail "a job runs on a floating -latest runner label:" "$gaps"

gaps="$(tool_pin_gaps "$WORKFLOW_DIR/shell-lint.yml" "$PROJECT_ROOT/.pre-commit-config.yaml" \
    "$PROJECT_ROOT/uv.lock" "$PROJECT_ROOT/pyproject.toml" "$PROJECT_ROOT/.github")"
[ -z "$gaps" ] || contract_fail "a tool is pinned differently in two places:" "$gaps"

contract_pass "every action has one SHA and tag, shfmt/shellcheck/ruff/python agree across CI, pre-commit, and uv.lock, and every runner label is versioned."
