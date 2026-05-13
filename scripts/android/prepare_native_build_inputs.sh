#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_PROJECT_DIR="$PROJECT_ROOT/android"
DEFAULTS_FILE="$ANDROID_PROJECT_DIR/r47-defaults.properties"
STAGED_CPP_DIR="${R47_ANDROID_STAGED_CPP_DIR:-$ANDROID_PROJECT_DIR/.staged-native/cpp}"
UPSTREAM_REMOTE_NAME="r47-c43-upstream"
FONT_ASSET_FILES=(
    "C47__NumericFont.ttf"
    "C47__StandardFont.ttf"
    "C47__TinyFont.ttf"
    "sortingOrder.xlsx"
)
SIM_REQUIRED_SUBDIRS=(
    "src/c47"
    "src/ttf2RasterFonts"
    "src/generateConstants"
    "src/generateCatalogs"
    "src/generateTestPgms"
    "src/testSuite"
    "src/c47-gtk"
)
SIM_REQUIRED_SOURCE_DIRS=(
    "src/generated"
)
SIM_REQUIRED_ROOT_FILES=(
    "meson.build"
    "meson_options.txt"
    "dep/meson.build"
    "dep/forcecrc32.c"
    "docs/code/meson.build"
)

RESOLVED_UPSTREAM_URL=""
RESOLVED_UPSTREAM_COMMIT=""
RESOLVED_UPSTREAM_SHORT_COMMIT=""

usage() {
    cat <<'EOF'
Usage:
    scripts/android/prepare_native_build_inputs.sh
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

read_default_value() {
    local key="$1"
    local value=""

    value=$(sed -n "s/^${key}=//p" "$DEFAULTS_FILE" | tail -n1)
    [ -n "$value" ] || fail "Missing ${key} in ${DEFAULTS_FILE}"
    printf '%s\n' "$value"
}

normalize_job_count() {
    local value="$1"

    case "$value" in
        ''|*[!0-9]*)
            return 1
            ;;
        0)
            return 1
            ;;
    esac

    printf '%s\n' "$value"
}

detect_job_count() {
    if command -v nproc >/dev/null 2>&1; then
        nproc
        return 0
    fi

    if command -v getconf >/dev/null 2>&1; then
        getconf _NPROCESSORS_ONLN
        return 0
    fi

    printf '1\n'
}

resolve_job_count() {
    local detected_jobs=""

    if detected_jobs=$(normalize_job_count "${R47_BUILD_JOBS-}"); then
        printf '%s\n' "$detected_jobs"
        return 0
    fi

    if detected_jobs=$(normalize_job_count "${CMAKE_BUILD_PARALLEL_LEVEL-}"); then
        printf '%s\n' "$detected_jobs"
        return 0
    fi

    detected_jobs=$(detect_job_count)
    normalize_job_count "$detected_jobs" || printf '1\n'
}

font_source_dir_has_required_fonts() {
    local candidate_dir="$1"
    local font_file=""

    [ -d "$candidate_dir" ] || return 1

    for font_file in "${FONT_ASSET_FILES[@]}"; do
        [ -f "$candidate_dir/$font_file" ] || return 1
    done

    return 0
}

resolve_upstream_state() {
    local resolved_env=""

    resolved_env=$(bash "$PROJECT_ROOT/scripts/upstream-sync/upstream.sh" resolve --auto --write-lock)
    eval "$resolved_env"

    RESOLVED_UPSTREAM_URL="$R47_RESOLVED_UPSTREAM_URL"
    RESOLVED_UPSTREAM_COMMIT="$R47_RESOLVED_UPSTREAM_COMMIT"
    RESOLVED_UPSTREAM_SHORT_COMMIT=$(printf '%.8s\n' "$RESOLVED_UPSTREAM_COMMIT")
}

ensure_remote() {
    if git -C "$PROJECT_ROOT" remote | grep -qx "$UPSTREAM_REMOTE_NAME"; then
        git -C "$PROJECT_ROOT" remote set-url "$UPSTREAM_REMOTE_NAME" "$RESOLVED_UPSTREAM_URL"
    else
        git -C "$PROJECT_ROOT" remote add "$UPSTREAM_REMOTE_NAME" "$RESOLVED_UPSTREAM_URL"
    fi
}

hydrate_missing_upstream_paths() {
    local need_fonts="false"
    local -a archive_paths=()
    local sim_subdir=""
    local required_dir=""
    local required_file=""

    for sim_subdir in "${SIM_REQUIRED_SUBDIRS[@]}"; do
        if [ ! -d "$PROJECT_ROOT/$sim_subdir" ]; then
            archive_paths+=("$sim_subdir")
            continue
        fi

        if [ ! -f "$PROJECT_ROOT/$sim_subdir/meson.build" ]; then
            archive_paths+=("$sim_subdir/meson.build")
        fi
    done

    for required_dir in "${SIM_REQUIRED_SOURCE_DIRS[@]}"; do
        if [ ! -d "$PROJECT_ROOT/$required_dir" ]; then
            archive_paths+=("$required_dir")
        fi
    done

    for required_file in "${SIM_REQUIRED_ROOT_FILES[@]}"; do
        if [ ! -f "$PROJECT_ROOT/$required_file" ]; then
            archive_paths+=("$required_file")
        fi
    done

    if [ ! -d "$PROJECT_ROOT/dep/decNumberICU" ] || [ ! -f "$PROJECT_ROOT/dep/decNumberICU/ICU-license.html" ]; then
        archive_paths+=("dep/decNumberICU")
    fi

    if ! font_source_dir_has_required_fonts "$PROJECT_ROOT/res/fonts"; then
        need_fonts="true"
        archive_paths+=(res/fonts)
    fi

    if [ "${#archive_paths[@]}" -eq 0 ] && [ "$need_fonts" = "false" ]; then
        return 0
    fi

    echo "--- Hydrating missing upstream paths for direct Gradle build ---"
    ensure_remote
    git -C "$PROJECT_ROOT" fetch --depth 1 "$UPSTREAM_REMOTE_NAME" "$RESOLVED_UPSTREAM_COMMIT"
    git -C "$PROJECT_ROOT" archive --format=tar "$RESOLVED_UPSTREAM_COMMIT" "${archive_paths[@]}" | tar -C "$PROJECT_ROOT" -xf -

    for sim_subdir in "${SIM_REQUIRED_SUBDIRS[@]}"; do
        [ -f "$PROJECT_ROOT/$sim_subdir/meson.build" ] || fail "Missing simulator Meson entrypoint at $PROJECT_ROOT/$sim_subdir/meson.build after targeted hydration."
    done
    for required_dir in "${SIM_REQUIRED_SOURCE_DIRS[@]}"; do
        [ -d "$PROJECT_ROOT/$required_dir" ] || fail "Missing required upstream source directory at $PROJECT_ROOT/$required_dir after targeted hydration."
    done
    for required_file in "${SIM_REQUIRED_ROOT_FILES[@]}"; do
        [ -f "$PROJECT_ROOT/$required_file" ] || fail "Missing required upstream build input at $PROJECT_ROOT/$required_file after targeted hydration."
    done
    [ -d "$PROJECT_ROOT/dep/decNumberICU" ] || fail "Missing decNumberICU source tree at $PROJECT_ROOT/dep/decNumberICU after targeted hydration."
    [ -f "$PROJECT_ROOT/dep/decNumberICU/ICU-license.html" ] || fail "Missing decNumberICU notice file at $PROJECT_ROOT/dep/decNumberICU/ICU-license.html after targeted hydration."
    font_source_dir_has_required_fonts "$PROJECT_ROOT/res/fonts" || fail "Missing canonical calculator fonts at $PROJECT_ROOT/res/fonts after targeted hydration."
}

ensure_xlsxio_on_path() {
    local xlsxio_commit=""
    local cache_bin_dir=""

    if command -v xlsxio_xlsx2csv >/dev/null 2>&1; then
        return 0
    fi

    xlsxio_commit=$(read_default_value R47_DEFAULT_XLSXIO_COMMIT)
    cache_bin_dir="$HOME/.cache/r47/xlsxio/$xlsxio_commit/bin"

    if [ -x "$cache_bin_dir/xlsxio_xlsx2csv" ]; then
        export PATH="$cache_bin_dir:$PATH"
    fi
}

prepare_build_sim() {
    local job_count="$1"

    ensure_xlsxio_on_path

    bash "$SCRIPT_DIR/build_sim_assets.sh" --build-dir "$PROJECT_ROOT/build.sim" --jobs "$job_count"
}

stage_native_inputs() {
    echo "--- Staging Android native inputs into $STAGED_CPP_DIR ---"
    R47_CORE_HASH="$RESOLVED_UPSTREAM_SHORT_COMMIT" bash "$SCRIPT_DIR/stage_native_sources.sh" --cpp-dir "$STAGED_CPP_DIR"
}

main() {
    local job_count=""

    while [ "$#" -gt 0 ]; do
        case "$1" in
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown option: $1"
                ;;
        esac
    done

    job_count=$(resolve_job_count)
    resolve_upstream_state
    hydrate_missing_upstream_paths
    prepare_build_sim "$job_count"
    stage_native_inputs
}

main "$@"
