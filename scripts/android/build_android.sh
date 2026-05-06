#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_PROJECT_DIR="$PROJECT_ROOT/android"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"
ANDROID_SCRIPTS_DIR="$SCRIPTS_DIR/android"
STAGED_CPP_DIR="$ANDROID_PROJECT_DIR/.staged-native/cpp"
DEFAULTS_FILE="$ANDROID_PROJECT_DIR/r47-defaults.properties"
STAGED_INPUTS_FILE="$STAGED_CPP_DIR/STAGED-INPUTS.properties"
MINI_GMP_FALLBACK_DIR="$ANDROID_PROJECT_DIR/compat/mini-gmp-fallback"
FONT_ASSET_FILES=(
    "C47__NumericFont.ttf"
    "C47__StandardFont.ttf"
    "C47__TinyFont.ttf"
    "sortingOrder.xlsx"
)
RETIRED_LEGACY_CPP_PATHS=(
    "$ANDROID_PROJECT_DIR/app/src/main/cpp/c47"
    "$ANDROID_PROJECT_DIR/app/src/main/cpp/decNumberICU"
    "$ANDROID_PROJECT_DIR/app/src/main/cpp/generated"
    "$ANDROID_PROJECT_DIR/app/src/main/cpp/gmp"
    "$ANDROID_PROJECT_DIR/app/src/main/cpp/STAGED-SOURCE-MANIFEST.txt"
    "$ANDROID_PROJECT_DIR/app/src/main/cpp/staged_native_sources.cmake"
)

ANDROID_ONLY=false
DOCTOR_MODE=false
VERIFY_PACKAGING=false
VERIFY_PACKAGING_DIR=""

usage() {
    cat <<'EOF'
Usage: ./scripts/android/build_android.sh [--android-only] [--doctor] [--verify-packaging] [--verify-packaging-dir <dir>]

Modes:
    --android-only         Rebuild only the Android module after confirming staged native inputs are current.
    --doctor               Print SDK, NDK, CMake, xlsxio, upstream lock, and staged-input status.
EOF
}

fail() {
        echo "ERROR: $*" >&2
        exit 1
}

is_truthy() {
    case "${1:-}" in
        1|true|TRUE|yes|YES|on|ON)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

read_property_value() {
    local path="$1"
    local key="$2"

    [ -f "$path" ] || return 1
    sed -n "s/^${key}=//p" "$path" | tail -n1
}

load_android_defaults() {
    [ -f "$DEFAULTS_FILE" ] || fail "Missing Android defaults file at $DEFAULTS_FILE"

    # shellcheck disable=SC1090
    . "$DEFAULTS_FILE"
}

print_doctor_line() {
    printf '%-22s %s\n' "$1" "$2"
}

find_present_retired_legacy_cpp_paths() {
    local path=""

    for path in "${RETIRED_LEGACY_CPP_PATHS[@]}"; do
        if [ -e "$path" ]; then
            printf '%s\n' "$path"
        fi
    done
}

ensure_retired_legacy_cpp_paths_absent() {
    local present_paths=""

    present_paths=$(find_present_retired_legacy_cpp_paths)
    if [ -n "$present_paths" ]; then
        echo "ERROR: Retired Android native snapshot paths are present:" >&2
        printf '%s\n' "$present_paths" >&2
        fail "Retired app-module native snapshot paths must stay absent. Use $STAGED_CPP_DIR for staged Android inputs, $ANDROID_PROJECT_DIR/app/src/main/cpp/c47-android for Android-owned glue, and $MINI_GMP_FALLBACK_DIR for the tracked Android mini-gmp staging source."
    fi
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

resolve_font_source_dir() {
    local candidate_dir="$PROJECT_ROOT/res/fonts"

    if font_source_dir_has_required_fonts "$candidate_dir"; then
        printf '%s\n' "$candidate_dir"
        return 0
    fi

    return 1
}

upstream_sim_inputs_are_hydrated() {
    [ -d "$PROJECT_ROOT/src/c47" ] || return 1
    [ -f "$PROJECT_ROOT/src/c47/meson.build" ] || return 1
    [ -f "$PROJECT_ROOT/meson.build" ] || return 1
    [ -f "$PROJECT_ROOT/meson_options.txt" ] || return 1
    [ -f "$PROJECT_ROOT/dep/meson.build" ] || return 1
    [ -d "$PROJECT_ROOT/dep/decNumberICU" ] || return 1
    [ -f "$PROJECT_ROOT/dep/decNumberICU/ICU-license.html" ] || return 1
    resolve_font_source_dir >/dev/null 2>&1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --android-only)
            ANDROID_ONLY=true
            shift
            ;;
        --doctor)
            DOCTOR_MODE=true
            shift
            ;;
        --verify-packaging)
            VERIFY_PACKAGING=true
            shift
            ;;
        --verify-packaging-dir)
            VERIFY_PACKAGING=true
            VERIFY_PACKAGING_DIR="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

on_error() {
    local exit_code="$?"
    local line_no="${1:-${BASH_LINENO[0]:-$LINENO}}"

    echo "ERROR: scripts/android/build_android.sh failed with exit code ${exit_code} at line ${line_no} while running: ${BASH_COMMAND}" >&2
    exit "$exit_code"
}

trap 'on_error "${BASH_LINENO[0]:-$LINENO}"' ERR

# =============================================================================
# R47 Android Build Script
# =============================================================================

# --- 1. Setup Variables ---
resolve_path() {
    local target="$1"
    local dir

    while [ -L "$target" ]; do
        dir=$(cd -P "$(dirname "$target")" && pwd)
        target=$(readlink "$target")
        case "$target" in
            /*) ;;
            *) target="$dir/$target" ;;
        esac
    done

    dir=$(cd -P "$(dirname "$target")" && pwd)
    printf '%s/%s\n' "$dir" "$(basename "$target")"
}

if [ -n "${JAVA_HOME-}" ] && [ ! -d "$JAVA_HOME" ]; then
    unset JAVA_HOME
fi

if [ -z "${JAVA_HOME-}" ]; then
    if [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
        JAVA_HOME_CANDIDATE=$(/usr/libexec/java_home 2>/dev/null || true)
        if [ -d "$JAVA_HOME_CANDIDATE" ]; then
            export JAVA_HOME="$JAVA_HOME_CANDIDATE"
            echo "Detected JAVA_HOME from /usr/libexec/java_home: $JAVA_HOME"
        fi
    fi
fi

if [ -z "${JAVA_HOME-}" ]; then
    JAVA_BIN=$(command -v java 2>/dev/null || true)
    if [ -n "$JAVA_BIN" ]; then
        JAVA_HOME_CANDIDATE=$(dirname "$(dirname "$(resolve_path "$JAVA_BIN")")")
        if [ -d "$JAVA_HOME_CANDIDATE" ]; then
            export JAVA_HOME="$JAVA_HOME_CANDIDATE"
            echo "Detected JAVA_HOME from PATH: $JAVA_HOME"
        fi
    fi
fi

if [ -z "${JAVA_HOME-}" ] && [ -d /usr/lib/jvm ]; then
    for jvm in /usr/lib/jvm/default-java /usr/lib/jvm/*; do
        if [ -d "$jvm" ] && [ -x "$jvm/bin/java" ]; then
            export JAVA_HOME="$jvm"
            echo "Detected JAVA_HOME from /usr/lib/jvm: $JAVA_HOME"
            break
        fi
    done
fi

if [ -n "${JAVA_HOME-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
else
    echo "WARNING: No local Java installation detected. Gradle build requires JDK 17+."
fi

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

R47_GRADLE_RUNNER_NOTICE_EMITTED=false

ensure_repo_gradle_wrapper() {
    [ -x "$ANDROID_PROJECT_DIR/gradlew" ] || \
        fail "Missing repo Gradle wrapper launcher at $ANDROID_PROJECT_DIR/gradlew. Restore the tracked wrapper files for Gradle $R47_DEFAULT_ANDROID_GRADLE_VERSION instead of using a host gradle command."
}

run_gradle() {
    ensure_repo_gradle_wrapper

    if [ "$R47_GRADLE_RUNNER_NOTICE_EMITTED" = false ]; then
        echo "--- Using repo Gradle wrapper launcher: $ANDROID_PROJECT_DIR/gradlew ---"
        R47_GRADLE_RUNNER_NOTICE_EMITTED=true
    fi

    "$ANDROID_PROJECT_DIR/gradlew" "$@"
}

R47_BUILD_JOBS_INPUT=${R47_BUILD_JOBS-}
if ! R47_BUILD_JOBS=$(normalize_job_count "$R47_BUILD_JOBS_INPUT"); then
    CMAKE_BUILD_JOBS_INPUT=${CMAKE_BUILD_PARALLEL_LEVEL-}
    if ! R47_BUILD_JOBS=$(normalize_job_count "$CMAKE_BUILD_JOBS_INPUT"); then
        R47_BUILD_JOBS=$(detect_job_count)
    fi
fi

export R47_BUILD_JOBS
export CMAKE_BUILD_PARALLEL_LEVEL="$R47_BUILD_JOBS"

detect_android_sdk_root() {
    local candidate=""

    for candidate in "${ANDROID_SDK_ROOT-}" "${ANDROID_HOME-}" "$HOME/.android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate" ] && { [ -d "$candidate/platform-tools" ] || [ -d "$candidate/ndk" ]; }; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

resolve_upstream_state() {
    local resolved_env=""

    resolved_env=$(bash "$SCRIPTS_DIR/upstream-sync/upstream.sh" resolve --auto --write-lock)
    eval "$resolved_env"

    RESOLVED_UPSTREAM_URL="$R47_RESOLVED_UPSTREAM_URL"
    RESOLVED_UPSTREAM_COMMIT="$R47_RESOLVED_UPSTREAM_COMMIT"
    RESOLVED_UPSTREAM_SHORT_COMMIT=$(printf '%.8s\n' "$RESOLVED_UPSTREAM_COMMIT")
}

ensure_upstream_core_hydrated() {
    if upstream_sim_inputs_are_hydrated; then
        return 0
    fi

    echo "--- Hydrating resolved upstream core, shared root build inputs, and canonical calculator fonts ---"
    bash "$SCRIPTS_DIR/upstream-sync/upstream.sh" sync --auto --write-lock --if-missing

    upstream_sim_inputs_are_hydrated || \
        fail "Incomplete upstream core or shared root build inputs after sync. Run ./scripts/upstream-sync/upstream.sh sync --auto --write-lock in a clean worktree before running ./scripts/android/build_android.sh."
}

ensure_xlsxio_toolchain() {
    local xlsxio_prefix="${R47_XLSXIO_PREFIX:-$HOME/.cache/r47/xlsxio/$R47_XLSXIO_COMMIT}"
    local xlsxio_dir="${TMPDIR:-/tmp}/r47-xlsxio-$R47_XLSXIO_COMMIT"
    local minizip_prefix=""
    local cmake_args=(
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5
        -DBUILD_STATIC=ON
        -DBUILD_SHARED=OFF
        -DBUILD_DOCUMENTATION=FALSE
        -DBUILD_EXAMPLES=FALSE
        -DBUILD_TOOLS=ON
        -DWITH_LIBZIP=OFF
    )

    if command -v xlsxio_xlsx2csv >/dev/null 2>&1; then
        return 0
    fi

    if [ -x "$xlsxio_prefix/bin/xlsxio_xlsx2csv" ]; then
        export PATH="$xlsxio_prefix/bin:$PATH"
        return 0
    fi

    echo "--- Bootstrapping pinned xlsxio toolchain ---"
    rm -rf "$xlsxio_dir"
    git init --initial-branch=main "$xlsxio_dir" >/dev/null
    git -C "$xlsxio_dir" remote add origin "$R47_XLSXIO_URL"
    git -C "$xlsxio_dir" fetch --depth 1 origin "$R47_XLSXIO_COMMIT"
    git -C "$xlsxio_dir" checkout --detach FETCH_HEAD >/dev/null

    mkdir -p "$xlsxio_prefix"
    if ! pkg-config --exists minizip 2>/dev/null; then
        minizip_prefix=$(ensure_local_minizip_prefix) || return 1
        cmake_args+=("-DMINIZIP_DIR=$minizip_prefix")
    fi

    "$R47_CMAKE_BIN" -S "$xlsxio_dir" -B "$xlsxio_dir/build" "${cmake_args[@]}"
    "$R47_CMAKE_BIN" --build "$xlsxio_dir/build" --parallel "$R47_BUILD_JOBS"
    "$R47_CMAKE_BIN" --install "$xlsxio_dir/build" --prefix "$xlsxio_prefix"

    export PATH="$xlsxio_prefix/bin:$PATH"
    command -v xlsxio_xlsx2csv >/dev/null 2>&1
}

ensure_local_minizip_prefix() {
    local minizip_prefix="${R47_MINIZIP_PREFIX:-$HOME/.cache/r47/minizip/dev}"
    local deb_dir="${TMPDIR:-/tmp}/r47-minizip-deb"
    local extract_dir="$deb_dir/extract"
    local deb_file=""
    local lib_file=""

    if [ -f "$minizip_prefix/include/minizip/unzip.h" ] && [ -f "$minizip_prefix/lib/libminizip.a" ]; then
        printf '%s\n' "$minizip_prefix"
        return 0
    fi

    if ! command -v apt-get >/dev/null 2>&1 || ! command -v dpkg-deb >/dev/null 2>&1; then
        echo "ERROR: Automatic minizip bootstrap requires apt-get and dpkg-deb. Install a static minizip development package manually and set R47_MINIZIP_PREFIX to a prefix containing include/minizip/unzip.h and lib/libminizip.a." >&2
        return 1
    fi

    rm -rf "$deb_dir"
    mkdir -p "$deb_dir"

    if ! (
        cd "$deb_dir"
        apt-get download libminizip-dev >/dev/null
    ); then
        echo "ERROR: Failed to download libminizip-dev for the pinned xlsxio bootstrap. Install minizip development files manually or set R47_MINIZIP_PREFIX." >&2
        return 1
    fi

    deb_file=$(find "$deb_dir" -maxdepth 1 -name 'libminizip-dev_*.deb' | head -n1)
    if [ -z "$deb_file" ]; then
        echo "ERROR: libminizip-dev download completed without producing a .deb payload. Install minizip development files manually or set R47_MINIZIP_PREFIX." >&2
        return 1
    fi

    dpkg-deb -x "$deb_file" "$extract_dir"
    lib_file=$(find "$extract_dir" -path '*/libminizip.a' | head -n1)
    if [ -z "$lib_file" ]; then
        echo "ERROR: Extracted libminizip-dev payload did not contain libminizip.a. Install a compatible static minizip package manually or set R47_MINIZIP_PREFIX." >&2
        return 1
    fi

    mkdir -p "$minizip_prefix/include/minizip" "$minizip_prefix/lib"
    cp -f "$extract_dir"/usr/include/minizip/*.h "$minizip_prefix/include/minizip/"
    cp -f "$lib_file" "$minizip_prefix/lib/"

    printf '%s\n' "$minizip_prefix"
}

resolve_cmake_bin() {
    local candidate=""
    local sdk_root="${ANDROID_SDK_ROOT-}"

    if command -v cmake >/dev/null 2>&1; then
        command -v cmake
        return 0
    fi

    if [ -n "$sdk_root" ]; then
        for candidate in "$sdk_root/cmake/$R47_CMAKE_VERSION/bin/cmake" "$sdk_root"/cmake/*/bin/cmake; do
            if [ -x "$candidate" ]; then
                printf '%s\n' "$candidate"
                return 0
            fi
        done
    fi

    return 1
}

ensure_canonical_font_assets_available() {
    local source_dir=""

    source_dir=$(resolve_font_source_dir) || fail "Missing canonical calculator font assets at $PROJECT_ROOT/res/fonts. Run ./scripts/upstream-sync/upstream.sh sync --auto --write-lock or ./scripts/android/build_android.sh in full mode in a clean worktree before building."
    echo "--- Using canonical calculator fonts from $source_dir ---"
}

write_local_properties() {
    echo "sdk.dir=$ANDROID_SDK_ROOT" > "$ANDROID_PROJECT_DIR/local.properties"
}

compute_current_staged_inputs() {
    local output_path="$1"

    bash "$ANDROID_SCRIPTS_DIR/compute_staged_native_inputs.sh" --output "$output_path"
}

ensure_staged_inputs_current() {
    local current_inputs_file=""
    local current_fingerprint=""
    local staged_fingerprint=""

    [ -f "$STAGED_INPUTS_FILE" ] || fail "Missing staged native input fingerprint at $STAGED_INPUTS_FILE. Run ./scripts/android/build_android.sh without --android-only first."
    [ -f "$STAGED_CPP_DIR/staged_native_sources.cmake" ] || fail "Missing staged native source list under $STAGED_CPP_DIR. Run ./scripts/android/build_android.sh without --android-only first."
    [ -f "$STAGED_CPP_DIR/STAGED-SOURCE-MANIFEST.txt" ] || fail "Missing staged native manifest under $STAGED_CPP_DIR. Run ./scripts/android/build_android.sh without --android-only first."

    current_inputs_file=$(mktemp)
    if ! compute_current_staged_inputs "$current_inputs_file" >/dev/null 2>&1; then
        rm -f "$current_inputs_file"
        fail "Unable to compute current native input fingerprints. Run ./scripts/android/build_android.sh without --android-only to regenerate build.sim outputs and refresh $STAGED_CPP_DIR."
    fi

    staged_fingerprint=$(read_property_value "$STAGED_INPUTS_FILE" R47_STAGED_INPUTS_COMBINED_FINGERPRINT || true)
    current_fingerprint=$(read_property_value "$current_inputs_file" R47_STAGED_INPUTS_COMBINED_FINGERPRINT || true)
    rm -f "$current_inputs_file"

    [ -n "$staged_fingerprint" ] || fail "Staged native fingerprint file at $STAGED_INPUTS_FILE is invalid."
    [ -n "$current_fingerprint" ] || fail "Current native fingerprint computation returned no result."

    if [ "$staged_fingerprint" != "$current_fingerprint" ]; then
        fail "Android build-only staging is stale. Run ./scripts/android/build_android.sh without --android-only to refresh $STAGED_CPP_DIR."
    fi
}

print_doctor_report() {
    local doctor_failed=false
    local cmake_status=""
    local xlsxio_status=""
    local xlsxio_cached_path="$HOME/.cache/r47/xlsxio/$R47_XLSXIO_COMMIT/bin/xlsxio_xlsx2csv"
    local lock_commit=""
    local lock_url=""
    local source_url=""
    local stage_core_version=""
    local staged_fingerprint=""
    local current_fingerprint=""
    local current_inputs_file=""
    local legacy_cpp_paths=""
    local font_source_dir=""
    local font_source_status=""

    echo "R47 Android Doctor"
    echo "=================="
    print_doctor_line "defaults" "$DEFAULTS_FILE"

    if [ -d "$ANDROID_SDK_ROOT" ]; then
        print_doctor_line "sdk root" "$ANDROID_SDK_ROOT"
    else
        print_doctor_line "sdk root" "missing ($ANDROID_SDK_ROOT)"
        doctor_failed=true
    fi

    if [ -d "$ANDROID_SDK_ROOT/platforms/android-$R47_DEFAULT_ANDROID_COMPILE_SDK" ]; then
        print_doctor_line "compile sdk" "android-$R47_DEFAULT_ANDROID_COMPILE_SDK present"
    else
        print_doctor_line "compile sdk" "android-$R47_DEFAULT_ANDROID_COMPILE_SDK missing"
        doctor_failed=true
    fi

    if [ -d "$ANDROID_SDK_ROOT/build-tools/$R47_DEFAULT_ANDROID_BUILD_TOOLS_VERSION" ]; then
        print_doctor_line "build tools" "$R47_DEFAULT_ANDROID_BUILD_TOOLS_VERSION present"
    else
        print_doctor_line "build tools" "$R47_DEFAULT_ANDROID_BUILD_TOOLS_VERSION missing"
        doctor_failed=true
    fi

    if [ -n "$IF_NDK_VERSION" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$IF_NDK_VERSION" ]; then
        print_doctor_line "ndk" "$IF_NDK_VERSION present"
    else
        print_doctor_line "ndk" "$IF_NDK_VERSION missing"
        doctor_failed=true
    fi

    if [ -n "${R47_CMAKE_BIN:-}" ]; then
        cmake_status="$R47_CMAKE_BIN"
    else
        cmake_status="missing (wanted $R47_CMAKE_VERSION)"
        doctor_failed=true
    fi
    print_doctor_line "cmake" "$cmake_status"

    if command -v xlsxio_xlsx2csv >/dev/null 2>&1; then
        xlsxio_status="ready on PATH ($(command -v xlsxio_xlsx2csv))"
    elif [ -x "$xlsxio_cached_path" ]; then
        xlsxio_status="cached at $xlsxio_cached_path"
    else
        xlsxio_status="missing (want $R47_XLSXIO_COMMIT from $R47_XLSXIO_URL)"
        doctor_failed=true
    fi
    print_doctor_line "xlsxio" "$xlsxio_status"

    if font_source_dir=$(resolve_font_source_dir); then
        font_source_status="canonical root at $font_source_dir"
    else
        font_source_status="missing (need canonical $PROJECT_ROOT/res/fonts from authoritative upstream)"
        doctor_failed=true
    fi
    print_doctor_line "font source" "$font_source_status"

    lock_commit=$(read_property_value "$PROJECT_ROOT/upstream.lock" upstream_commit || true)
    lock_url=$(read_property_value "$PROJECT_ROOT/upstream.lock" upstream_url || true)
    source_url=$(read_property_value "$PROJECT_ROOT/upstream.source" upstream_url || true)

    if [ -n "$lock_commit" ]; then
        print_doctor_line "upstream lock" "$lock_commit (${lock_url:-$source_url})"
    elif [ -f "$PROJECT_ROOT/upstream.lock" ]; then
        print_doctor_line "upstream lock" "present without upstream_commit; will follow latest from ${source_url:-unknown}"
    else
        print_doctor_line "upstream lock" "absent; will follow latest from ${source_url:-unknown}"
    fi

    if [ -f "$STAGED_INPUTS_FILE" ]; then
        stage_core_version=$(read_property_value "$STAGED_INPUTS_FILE" R47_STAGED_CORE_VERSION || true)
        staged_fingerprint=$(read_property_value "$STAGED_INPUTS_FILE" R47_STAGED_INPUTS_COMBINED_FINGERPRINT || true)
        current_inputs_file=$(mktemp)
        if compute_current_staged_inputs "$current_inputs_file" >/dev/null 2>&1; then
            current_fingerprint=$(read_property_value "$current_inputs_file" R47_STAGED_INPUTS_COMBINED_FINGERPRINT || true)
            if [ -n "$staged_fingerprint" ] && [ "$staged_fingerprint" = "$current_fingerprint" ]; then
                print_doctor_line "staged inputs" "current${stage_core_version:+ (core $stage_core_version)}"
            else
                print_doctor_line "staged inputs" "stale${stage_core_version:+ (last core $stage_core_version)}"
                doctor_failed=true
            fi
        else
            print_doctor_line "staged inputs" "cannot verify current canonical inputs"
            doctor_failed=true
        fi
        rm -f "$current_inputs_file"
    else
        print_doctor_line "staged inputs" "missing ($STAGED_INPUTS_FILE)"
        doctor_failed=true
    fi

    legacy_cpp_paths=$(find_present_retired_legacy_cpp_paths)
    if [ -z "$legacy_cpp_paths" ]; then
        print_doctor_line "legacy cpp paths" "absent"
    else
        print_doctor_line "legacy cpp paths" "present ($(printf '%s\n' "$legacy_cpp_paths" | paste -sd', ' -))"
        doctor_failed=true
    fi

    if [ "$doctor_failed" = true ]; then
        exit 1
    fi
}

load_android_defaults

if ANDROID_SDK_ROOT_CANDIDATE=$(detect_android_sdk_root); then
    export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT_CANDIDATE"
else
    export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
fi

R47_CMAKE_VERSION=${R47_CMAKE_VERSION:-$R47_DEFAULT_ANDROID_CMAKE_VERSION}

# --- NDK Version Selection ---
IF_NDK_VERSION=${R47_NDK_VERSION:-$R47_DEFAULT_ANDROID_NDK_VERSION}

if [ -n "$IF_NDK_VERSION" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$IF_NDK_VERSION" ]; then
    echo "Using detected NDK version: $IF_NDK_VERSION"
    export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$IF_NDK_VERSION"
else
    # Fallback to latest available NDK
    LATEST_NDK=$(ls -1 "$ANDROID_SDK_ROOT/ndk" 2>/dev/null | sort -V | tail -n1 || true)
    if [ -n "$LATEST_NDK" ]; then
        echo "NDK $IF_NDK_VERSION not found. Falling back to latest: $LATEST_NDK"
        export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$LATEST_NDK"
    else
        echo "ERROR: No NDK found in $ANDROID_SDK_ROOT/ndk"
        exit 1
    fi
fi

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"

R47_XLSXIO_URL=${R47_XLSXIO_URL:-$R47_DEFAULT_XLSXIO_URL}
R47_XLSXIO_COMMIT=${R47_XLSXIO_COMMIT:-$R47_DEFAULT_XLSXIO_COMMIT}
if R47_CMAKE_BIN=$(resolve_cmake_bin); then
    export PATH="$(dirname "$R47_CMAKE_BIN"):$PATH"
else
    R47_CMAKE_BIN=""
fi

if [ "$DOCTOR_MODE" = true ]; then
    print_doctor_report
    exit 0
fi

[ -n "$R47_CMAKE_BIN" ] || fail "No usable cmake executable found. Install cmake or Android SDK CMake $R47_CMAKE_VERSION."
ensure_retired_legacy_cpp_paths_absent

if [ "$ANDROID_ONLY" = false ]; then
    resolve_upstream_state
    ensure_upstream_core_hydrated
    if ! ensure_xlsxio_toolchain; then
        echo "ERROR: Failed to provision xlsxio_xlsx2csv."
        exit 1
    fi
fi

echo "======================================================="
echo "R47 Android Builder"
echo "Mode: $( [ "$ANDROID_ONLY" = true ] && printf 'android-only' || printf 'full' )"
echo "SDK: $ANDROID_SDK_ROOT"
echo "NDK: $ANDROID_NDK_ROOT"
echo "Jobs: $R47_BUILD_JOBS"
echo "======================================================="

if [ "$ANDROID_ONLY" = true ]; then
    ensure_staged_inputs_current
    COMMIT_HASH=${R47_CORE_VERSION:-$(read_property_value "$STAGED_INPUTS_FILE" R47_STAGED_CORE_VERSION || true)}
    RESOLVED_UPSTREAM_URL=${R47_UPSTREAM_SOURCE_REPOSITORY_URL:-$(read_property_value "$PROJECT_ROOT/upstream.lock" upstream_url || read_property_value "$PROJECT_ROOT/upstream.source" upstream_url || true)}
    RESOLVED_UPSTREAM_COMMIT=${R47_UPSTREAM_SOURCE_COMMIT:-$(read_property_value "$PROJECT_ROOT/upstream.lock" upstream_commit || true)}
    [ -n "$COMMIT_HASH" ] || COMMIT_HASH="unknown"
    echo "--- Reusing current staged native inputs from $STAGED_CPP_DIR ---"
else
    COMMIT_HASH="$RESOLVED_UPSTREAM_SHORT_COMMIT"
    echo "--- SwissMicros Core Version (resolved): $COMMIT_HASH ---"

    bash "$ANDROID_SCRIPTS_DIR/build_sim_assets.sh" --build-dir "$PROJECT_ROOT/build.sim" --jobs "$R47_BUILD_JOBS"

    if ! R47_CORE_HASH="$COMMIT_HASH" bash "$ANDROID_SCRIPTS_DIR/stage_native_sources.sh" --cpp-dir "$STAGED_CPP_DIR"; then
        echo "ERROR: Android native staging failed."
        exit 1
    fi
fi

ensure_canonical_font_assets_available
write_local_properties

cd "$ANDROID_PROJECT_DIR"

# --- 4. Build APK ---
echo "--- Building APK ---"

# Pass detected NDK/SDK versions as Project Properties to override build.gradle defaults
GRADLE_PROPS="-Pr47.ndkVersion=$IF_NDK_VERSION"
GRADLE_PROPS="$GRADLE_PROPS -Pr47.coreVersion=$COMMIT_HASH"
COMPILE_SDK_OVERRIDE=${R47_COMPILE_SDK-}
VERSION_CODE_OVERRIDE=${R47_VERSION_CODE-}
VERSION_NAME_OVERRIDE=${R47_VERSION_NAME-}
SOURCE_REPOSITORY_URL_OVERRIDE=${R47_SOURCE_REPOSITORY_URL-}
SOURCE_COMMIT_OVERRIDE=${R47_SOURCE_COMMIT-}
UPSTREAM_SOURCE_REPOSITORY_URL_OVERRIDE=${R47_UPSTREAM_SOURCE_REPOSITORY_URL-}
UPSTREAM_SOURCE_COMMIT_OVERRIDE=${R47_UPSTREAM_SOURCE_COMMIT-}
XLSXIO_SOURCE_REPOSITORY_URL_VALUE=${R47_XLSXIO_URL-}
XLSXIO_SOURCE_COMMIT_VALUE=${R47_XLSXIO_COMMIT-}

if [ -z "$UPSTREAM_SOURCE_REPOSITORY_URL_OVERRIDE" ]; then
    UPSTREAM_SOURCE_REPOSITORY_URL_OVERRIDE="$RESOLVED_UPSTREAM_URL"
fi
if [ -z "$UPSTREAM_SOURCE_COMMIT_OVERRIDE" ]; then
    UPSTREAM_SOURCE_COMMIT_OVERRIDE="$RESOLVED_UPSTREAM_COMMIT"
fi

if [ -n "$COMPILE_SDK_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.compileSdk=$COMPILE_SDK_OVERRIDE"; fi
if [ -n "$VERSION_CODE_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.versionCode=$VERSION_CODE_OVERRIDE"; fi
if [ -n "$VERSION_NAME_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.versionName=$VERSION_NAME_OVERRIDE"; fi
if [ -n "$SOURCE_REPOSITORY_URL_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.sourceRepositoryUrl=$SOURCE_REPOSITORY_URL_OVERRIDE"; fi
if [ -n "$SOURCE_COMMIT_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.sourceCommit=$SOURCE_COMMIT_OVERRIDE"; fi
if [ -n "$UPSTREAM_SOURCE_REPOSITORY_URL_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.upstreamSourceRepositoryUrl=$UPSTREAM_SOURCE_REPOSITORY_URL_OVERRIDE"; fi
if [ -n "$UPSTREAM_SOURCE_COMMIT_OVERRIDE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.upstreamSourceCommit=$UPSTREAM_SOURCE_COMMIT_OVERRIDE"; fi
if [ -n "$XLSXIO_SOURCE_REPOSITORY_URL_VALUE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.xlsxioSourceRepositoryUrl=$XLSXIO_SOURCE_REPOSITORY_URL_VALUE"; fi
if [ -n "$XLSXIO_SOURCE_COMMIT_VALUE" ]; then GRADLE_PROPS="$GRADLE_PROPS -Pr47.xlsxioSourceCommit=$XLSXIO_SOURCE_COMMIT_VALUE"; fi
GRADLE_EXTRA_ARGS=${R47_GRADLE_ARGS-}

if [ -z "$SOURCE_REPOSITORY_URL_OVERRIDE" ]; then
    SOURCE_REPOSITORY_URL_OVERRIDE=$(git -C "$PROJECT_ROOT" config --get remote.origin.url 2>/dev/null || true)
fi
if [ -z "$SOURCE_COMMIT_OVERRIDE" ]; then
    SOURCE_COMMIT_OVERRIDE=$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || true)
fi
COMPILE_SDK_VALUE=${COMPILE_SDK_OVERRIDE:-$R47_DEFAULT_ANDROID_COMPILE_SDK}

if [ "$ANDROID_ONLY" = false ]; then
    rm -rf app/.cxx
    run_gradle clean $GRADLE_EXTRA_ARGS
fi
run_gradle --max-workers "$R47_BUILD_JOBS" assembleDebug $GRADLE_EXTRA_ARGS $GRADLE_PROPS
ensure_retired_legacy_cpp_paths_absent

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo "SUCCESS: APK created at: $ANDROID_PROJECT_DIR/$APK_PATH"
else
    echo "ERROR: APK build failed."
    exit 1
fi

if [ "$VERIFY_PACKAGING" = true ] || is_truthy "${R47_VERIFY_PACKAGING-}"; then
    PACKAGING_OUTPUT_DIR=${VERIFY_PACKAGING_DIR:-${R47_VERIFY_PACKAGING_DIR:-$ANDROID_PROJECT_DIR/build/outputs/packaging/debug}}
    PACKAGING_EXPECTED_ABIS=${R47_VERIFY_PACKAGING_ABIS:-arm64-v8a}
    bash "$ANDROID_SCRIPTS_DIR/collect_packaging_evidence.sh" \
        --variant debug \
        --apk "$ANDROID_PROJECT_DIR/$APK_PATH" \
        --output-dir "$PACKAGING_OUTPUT_DIR" \
        --artifact-name "R47calculator-debug.apk" \
        --expected-abis "$PACKAGING_EXPECTED_ABIS" \
        --android-sdk-root "$ANDROID_SDK_ROOT" \
        --ndk-version "$IF_NDK_VERSION" \
        --compile-sdk "$COMPILE_SDK_VALUE" \
        --cmake-version "$R47_CMAKE_VERSION" \
        --android-source-repository-url "$SOURCE_REPOSITORY_URL_OVERRIDE" \
        --android-source-commit "$SOURCE_COMMIT_OVERRIDE" \
        --upstream-source-repository-url "$UPSTREAM_SOURCE_REPOSITORY_URL_OVERRIDE" \
        --upstream-source-commit "$UPSTREAM_SOURCE_COMMIT_OVERRIDE" \
        --xlsxio-source-repository-url "$XLSXIO_SOURCE_REPOSITORY_URL_VALUE" \
        --xlsxio-source-commit "$XLSXIO_SOURCE_COMMIT_VALUE" \
        --signing-mode debug
fi
