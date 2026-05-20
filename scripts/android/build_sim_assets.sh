#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build.sim"
JOB_COUNT=""

usage() {
    cat <<'EOF'
Usage:
    scripts/android/build_sim_assets.sh [--build-dir <dir>] [--jobs <count>]
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

normalize_job_count() {
    case "$1" in
        ''|*[!0-9]*|0)
            return 1
            ;;
        *)
            printf '%s\n' "$1"
            ;;
    esac
}

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

resolve_job_count() {
    if [ -n "$JOB_COUNT" ]; then
        normalize_job_count "$JOB_COUNT" || fail "Invalid --jobs value: $JOB_COUNT"
        return 0
    fi

    if JOB_COUNT=$(normalize_job_count "${R47_BUILD_JOBS-}"); then
        printf '%s\n' "$JOB_COUNT"
        return 0
    fi

    if JOB_COUNT=$(normalize_job_count "${CMAKE_BUILD_PARALLEL_LEVEL-}"); then
        printf '%s\n' "$JOB_COUNT"
        return 0
    fi

    JOB_COUNT=$(detect_job_count)
    printf '%s\n' "$JOB_COUNT"
}

ensure_required_tools() {
    command -v meson >/dev/null 2>&1 || fail "meson is required on PATH."
    command -v ninja >/dev/null 2>&1 || fail "ninja is required on PATH."
    command -v xlsxio_xlsx2csv >/dev/null 2>&1 || fail "xlsxio_xlsx2csv is required on PATH before generating Android core assets."
}

configure_build_dir() {
    if [ -d "$BUILD_DIR" ] && [ ! -f "$BUILD_DIR/build.ninja" ]; then
        rm -rf "$BUILD_DIR"
    fi

    if [ -f "$BUILD_DIR/build.ninja" ]; then
        meson setup "$BUILD_DIR" \
            --reconfigure \
            --buildtype=custom \
            -DRASPBERRY=false \
            -DDECNUMBER_FASTMUL=true
        return 0
    fi

    meson setup "$BUILD_DIR" \
        --buildtype=custom \
        -DRASPBERRY=false \
        -DDECNUMBER_FASTMUL=true
}

main() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --build-dir)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --build-dir"
                BUILD_DIR="$1"
                ;;
            --jobs)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --jobs"
                JOB_COUNT="$1"
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown option: $1"
                ;;
        esac
        shift
    done

    JOB_COUNT=$(resolve_job_count)
    ensure_required_tools

    echo "--- Generating core assets (Meson/Ninja sim target) ---"
    configure_build_dir
    ninja -C "$BUILD_DIR" -j "$JOB_COUNT" sim
}

main "$@"