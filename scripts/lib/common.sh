#!/bin/bash

# Shared helpers sourced by repo-owned build and staging scripts.
#
# Source after SCRIPT_DIR is set:
#   source "$SCRIPT_DIR/../lib/common.sh"
#
# Functions here must stay self-contained (no caller-provided globals) so any
# script one level under scripts/ can source this file safely.

# Echo a positive integer job count, or fail (return 1) for empty/non-numeric/0.
normalize_job_count() {
    case "$1" in
        '' | *[!0-9]* | 0)
            return 1
            ;;
        *)
            printf '%s\n' "$1"
            ;;
    esac
}

# Detect the host CPU count, falling back to 1 when it cannot be determined.
detect_job_count() {
    local detected_jobs=""

    if command -v nproc >/dev/null 2>&1; then
        detected_jobs=$(nproc 2>/dev/null || true)
    fi

    if [ -z "$detected_jobs" ] && command -v getconf >/dev/null 2>&1; then
        detected_jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)
    fi

    if ! detected_jobs=$(normalize_job_count "$detected_jobs"); then
        detected_jobs=1
    fi

    printf '%s\n' "$detected_jobs"
}

# Sum the JUnit "tests" counts across every result XML under a directory tree.
# Echoes the total (0 when the directory is missing or holds no results). Used
# to guard an instrumentation selection against silently executing nothing --
# e.g. a hardcoded -e class filter whose class was renamed or removed, which
# some AndroidJUnitRunner versions report as zero tests with a success exit.
count_androidtest_cases() {
    local results_dir="$1"
    local total=0 n
    [ -d "$results_dir" ] || { printf '0\n'; return 0; }
    while IFS= read -r n; do
        [ -n "$n" ] && total=$((total + n))
    done < <(
        find "$results_dir" -type f -name '*.xml' -print0 2>/dev/null \
            | xargs -0 -r grep -hoE '<testsuite[^>]* tests="[0-9]+"' 2>/dev/null \
            | grep -oE '[0-9]+'
    )
    printf '%s\n' "$total"
}
