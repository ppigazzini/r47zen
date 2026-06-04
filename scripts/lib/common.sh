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
