#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UPSTREAM_REMOTE_NAME="r47-c43-upstream"
OUTPUT_DIR=""
TEMP_DIR=""

usage() {
    cat <<'EOF'
Usage:
    scripts/android/stage_program_fixture_assets.sh --output-dir <directory>
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

cleanup() {
    if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
        rm -rf "$TEMP_DIR"
    fi
}

trap cleanup EXIT

ensure_remote() {
    local upstream_url="$1"

    if git -C "$PROJECT_ROOT" remote | grep -qx "$UPSTREAM_REMOTE_NAME"; then
        git -C "$PROJECT_ROOT" remote set-url "$UPSTREAM_REMOTE_NAME" "$upstream_url"
    else
        git -C "$PROJECT_ROOT" remote add "$UPSTREAM_REMOTE_NAME" "$upstream_url"
    fi
}

resolve_upstream_state() {
    local resolved_env=""

    resolved_env="$(bash "$PROJECT_ROOT/scripts/upstream-sync/upstream.sh" resolve --auto --write-lock)"
    eval "$resolved_env"
}

stage_program_fixtures() {
    TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/r47-program-fixtures.XXXXXX")"

    ensure_remote "$R47_RESOLVED_UPSTREAM_URL"
    git -C "$PROJECT_ROOT" fetch --depth 1 "$UPSTREAM_REMOTE_NAME" "$R47_RESOLVED_UPSTREAM_COMMIT"
    git -C "$PROJECT_ROOT" archive --format=tar "$R47_RESOLVED_UPSTREAM_COMMIT" res/PROGRAMS | tar -C "$TEMP_DIR" -xf -

    [[ -d "$TEMP_DIR/res/PROGRAMS" ]] || \
        fail "Resolved upstream ${R47_RESOLVED_UPSTREAM_COMMIT} does not contain res/PROGRAMS"

    rm -rf "$OUTPUT_DIR"
    mkdir -p "$OUTPUT_DIR/PROGRAMS"
    cp -R "$TEMP_DIR/res/PROGRAMS/." "$OUTPUT_DIR/PROGRAMS/"
}

main() {
    while [[ "$#" -gt 0 ]]; do
        case "$1" in
            --output-dir)
                [[ "$#" -ge 2 ]] || fail "Missing value for --output-dir"
                OUTPUT_DIR="$2"
                shift 2
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown option: $1"
                ;;
        esac
    done

    [[ -n "$OUTPUT_DIR" ]] || fail "--output-dir is required"

    resolve_upstream_state
    stage_program_fixtures
}

main "$@"
