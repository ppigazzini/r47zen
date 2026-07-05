#!/bin/bash

# Shared helpers for the run_*_contract.sh CI guards. Source after computing the
# caller's script dir:
#
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   # shellcheck source=scripts/lib/ci_contract.sh
#   source "$SCRIPT_DIR/../lib/ci_contract.sh"
#
# Sourcing sets PROJECT_ROOT and WORKFLOW_DIR (resolved from this file's own
# location, so they are correct regardless of which contract sources it).
# Functions are self-contained.

CI_CONTRACT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$CI_CONTRACT_LIB_DIR/../.." && pwd)"
WORKFLOW_DIR="$PROJECT_ROOT/.github/workflows"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"
export PROJECT_ROOT WORKFLOW_DIR SCRIPTS_DIR

# grep -rnE across the workflow tree, dropping YAML comment lines so a directive
# that only appears in a "# ..." comment never satisfies or violates a contract.
# This is the single home for the comment-stripping that several contracts used
# to re-implement by hand (and shipped wrong twice). Prints "file:line:match";
# returns grep's status (0 = at least one real match).
workflow_grep() {
    grep -rnE -- "$1" "$WORKFLOW_DIR" 2>/dev/null |
        grep -vE ':[0-9]+:[[:space:]]*#'
}

# grep -rnE across repo-owned shell scripts, dropping shell comment lines the
# same comment-safe way as workflow_grep. The workflows invoke these scripts, so
# a security guard that only scans workflow YAML misses an anti-pattern moved
# into a script. EXCLUDE names lets a caller drop files that legitimately embed
# the pattern (e.g. the guard that defines it). Prints "file:line:match".
scripts_grep() {
    local pattern="$1"
    shift || true
    local exclude_args=()
    local name
    for name in "$@"; do
        exclude_args+=(--exclude="$name")
    done
    grep -rnE --include='*.sh' "${exclude_args[@]}" -- "$pattern" "$SCRIPTS_DIR" 2>/dev/null |
        grep -vE ':[0-9]+:[[:space:]]*#'
}

# True (0) if PATTERN (ERE) appears on a non-comment line anywhere in the
# workflows.
workflow_uses() {
    local match
    match="$(workflow_grep "$1")"
    [ -n "$match" ]
}

# Print the YAML block of a top-level job (two-space indent) from FILE: from the
# "  <job>:" header to the next two-space job header or end of file.
workflow_job_block() {
    local file="$1" job="$2"
    awk -v hdr="^  ${job}:" '
        $0 ~ hdr { inblock = 1; print; next }
        inblock && /^  [A-Za-z0-9_-]+:/ { inblock = 0 }
        inblock { print }
    ' "$file"
}

# Drop YAML comment lines from stdin -- for filtering an in-memory job block the
# same comment-safe way as workflow_grep.
strip_yaml_comments() {
    grep -vE '^[[:space:]]*#'
}

# Standard reporters. contract_fail prints "FAIL: <msg>" plus any extra detail
# lines and exits 1; contract_pass prints "OK: <msg>" and exits 0.
contract_fail() {
    local msg="$1"
    shift || true
    echo "FAIL: $msg" >&2
    if [ "$#" -gt 0 ]; then
        printf '%s\n' "$@" >&2
    fi
    exit 1
}

contract_pass() {
    echo "OK: $1"
    exit 0
}
