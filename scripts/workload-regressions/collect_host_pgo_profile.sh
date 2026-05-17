#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"
DEFAULTS_FILE="$ANDROID_DIR/r47-defaults.properties"
LOCAL_PROPERTIES_FILE="$ANDROID_DIR/local.properties"
DEFAULT_OUTPUT_DIR="$ANDROID_DIR/build/host-pgo"
PROFILE_NAME="r47-host-core.profdata"
RAW_PROFILE_SUBDIR="raw"
HOST_BUILD_SUBDIR="build"
RESOURCE_SHIM_SUBDIR="resource-dir"
DEFAULT_GENERATED_PROGRAM_ROOT="$ANDROID_DIR/app/build/generated/assets/runtime/program-fixtures/PROGRAMS"
DEFAULT_UPSTREAM_PROGRAM_ROOT="$PROJECT_ROOT/res/PROGRAMS"

OUTPUT_DIR="$DEFAULT_OUTPUT_DIR"
PROGRAM_ROOT="${PROGRAM_ROOT:-}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
HOST_PROFILE_RUNTIME_PATH="${R47_HOST_PROFILE_RUNTIME_PATH:-}"

usage() {
    cat <<'EOF'
Usage: scripts/workload-regressions/collect_host_pgo_profile.sh [--output-dir <dir>] [--program-root <dir>] [--ndk-root <dir>]

Builds the staged Android-owned host workload regression harness with the pinned
NDK Clang and llvm-profdata pair, collects raw LLVM IRPGO profiles from the
canonical host fixture corpus, and merges them into an indexed .profdata file
that can be fed back into the Android Release native build.

The Android NDK does not ship the host Linux profiling runtime archive needed
to link -fprofile-generate binaries. This script therefore synthesizes a
temporary Clang resource-dir shim that reuses a host-installed
libclang_rt.profile archive. Override that archive explicitly with
R47_HOST_PROFILE_RUNTIME_PATH when needed.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

read_default_value() {
    local key="$1"
    local value=""

    [[ -f "$DEFAULTS_FILE" ]] || fail "Missing Android defaults file: $DEFAULTS_FILE"

    value=$(sed -n "s/^${key}=//p" "$DEFAULTS_FILE" | tail -n1)
    [[ -n "$value" ]] || fail "Missing ${key} in $DEFAULTS_FILE"
    printf '%s\n' "$value"
}

read_local_sdk_dir() {
    local value=""

    [[ -f "$LOCAL_PROPERTIES_FILE" ]] || return 1

    value=$(sed -n 's/^sdk\.dir=//p' "$LOCAL_PROPERTIES_FILE" | tail -n1)
    [[ -n "$value" ]] || return 1

    value=${value//\\:/:}
    value=${value//\\\\/\\}
    printf '%s\n' "$value"
}

resolve_android_sdk_root() {
    local sdk_root=""

    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" ]]; then
        printf '%s\n' "$sdk_root"
        return 0
    fi

    if sdk_root=$(read_local_sdk_dir); then
        printf '%s\n' "$sdk_root"
        return 0
    fi

    fail "Set ANDROID_SDK_ROOT or ANDROID_HOME, or write android/local.properties before collecting host PGO profiles."
}

resolve_android_ndk_root() {
    local sdk_root=""
    local ndk_version=""

    if [[ -n "$NDK_ROOT" ]]; then
        printf '%s\n' "$NDK_ROOT"
        return 0
    fi

    sdk_root=$(resolve_android_sdk_root)
    ndk_version=$(read_default_value 'R47_DEFAULT_ANDROID_NDK_VERSION')
    NDK_ROOT="$sdk_root/ndk/$ndk_version"

    [[ -d "$NDK_ROOT" ]] || fail "Pinned NDK not found at $NDK_ROOT"
    printf '%s\n' "$NDK_ROOT"
}

resolve_program_root() {
    if [[ -n "$PROGRAM_ROOT" ]]; then
        printf '%s\n' "$PROGRAM_ROOT"
        return 0
    fi

    if [[ -d "$DEFAULT_GENERATED_PROGRAM_ROOT" ]]; then
        printf '%s\n' "$DEFAULT_GENERATED_PROGRAM_ROOT"
        return 0
    fi

    printf '%s\n' "$DEFAULT_UPSTREAM_PROGRAM_ROOT"
}

resolve_llvm_tool() {
    local tool_name="$1"
    local ndk_root="$2"
    local tool_path="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/$tool_name"

    [[ -x "$tool_path" ]] || fail "Missing LLVM tool $tool_path"
    printf '%s\n' "$tool_path"
}

resolve_host_profile_runtime_suffix() {
    case "$(uname -m)" in
        x86_64|amd64)
            printf '%s\n' "x86_64"
            ;;
        aarch64|arm64)
            printf '%s\n' "aarch64"
            ;;
        i386|i686)
            printf '%s\n' "i386"
            ;;
        *)
            fail "Unsupported host architecture $(uname -m) for host profile runtime lookup. Set R47_HOST_PROFILE_RUNTIME_PATH explicitly."
            ;;
    esac
}

resolve_host_profile_runtime_path() {
    local runtime_suffix=""
    local runtime_path=""
    local resource_dir=""

    if [[ -n "$HOST_PROFILE_RUNTIME_PATH" ]]; then
        [[ -f "$HOST_PROFILE_RUNTIME_PATH" ]] || fail "Host profile runtime archive $HOST_PROFILE_RUNTIME_PATH does not exist."
        printf '%s\n' "$HOST_PROFILE_RUNTIME_PATH"
        return 0
    fi

    runtime_suffix=$(resolve_host_profile_runtime_suffix)

    if command -v clang >/dev/null 2>&1; then
        resource_dir="$(clang --print-resource-dir 2>/dev/null || true)"
        if [[ -n "$resource_dir" ]]; then
            runtime_path="$resource_dir/lib/linux/libclang_rt.profile-${runtime_suffix}.a"
            if [[ -f "$runtime_path" ]]; then
                printf '%s\n' "$runtime_path"
                return 0
            fi
        fi
    fi

    runtime_path="$({ find /usr/lib -path "*/lib/clang/*/lib/linux/libclang_rt.profile-${runtime_suffix}.a" 2>/dev/null || true; } | LC_ALL=C sort -V | tail -n 1)"
    [[ -n "$runtime_path" ]] || fail "Could not locate a host LLVM profiling runtime archive for ${runtime_suffix}. Install a host LLVM package that provides libclang_rt.profile-${runtime_suffix}.a or set R47_HOST_PROFILE_RUNTIME_PATH."
    printf '%s\n' "$runtime_path"
}

resolve_ndk_resource_dir() {
    local resource_dir=""

    resource_dir="$($CLANG_BIN --print-resource-dir)"
    [[ -d "$resource_dir" ]] || fail "NDK Clang resource dir $resource_dir does not exist."
    printf '%s\n' "$resource_dir"
}

prepare_resource_dir_shim() {
    local ndk_resource_dir="$1"
    local host_profile_runtime="$2"
    local shim_dir="$OUTPUT_DIR/$RESOURCE_SHIM_SUBDIR"
    local target_triple=""
    local entry=""
    local base_name=""

    target_triple="$($CLANG_BIN -print-target-triple)"
    [[ -n "$target_triple" ]] || fail "Failed to resolve host target triple from $CLANG_BIN"

    rm -rf "$shim_dir"
    mkdir -p "$shim_dir/lib"

    while IFS= read -r entry; do
        base_name="$(basename "$entry")"
        if [[ "$base_name" != "lib" ]]; then
            ln -s "$entry" "$shim_dir/$base_name"
        fi
    done < <(find "$ndk_resource_dir" -mindepth 1 -maxdepth 1 -print | LC_ALL=C sort)

    while IFS= read -r entry; do
        base_name="$(basename "$entry")"
        ln -s "$entry" "$shim_dir/lib/$base_name"
    done < <(find "$ndk_resource_dir/lib" -mindepth 1 -maxdepth 1 -print | LC_ALL=C sort)

    mkdir -p "$shim_dir/lib/$target_triple"
    ln -s "$host_profile_runtime" "$shim_dir/lib/$target_triple/libclang_rt.profile.a"

    printf '%s\n' "$shim_dir"
}

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --output-dir)
            shift
            [[ "$#" -gt 0 ]] || fail "Missing value for --output-dir"
            OUTPUT_DIR="$1"
            ;;
        --program-root)
            shift
            [[ "$#" -gt 0 ]] || fail "Missing value for --program-root"
            PROGRAM_ROOT="$1"
            ;;
        --ndk-root)
            shift
            [[ "$#" -gt 0 ]] || fail "Missing value for --ndk-root"
            NDK_ROOT="$1"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
    shift
done

NDK_ROOT=$(resolve_android_ndk_root)
PROGRAM_ROOT=$(resolve_program_root)
CLANG_BIN=$(resolve_llvm_tool clang "$NDK_ROOT")
LLVM_PROFDATA_BIN=$(resolve_llvm_tool llvm-profdata "$NDK_ROOT")
RAW_PROFILE_DIR="$OUTPUT_DIR/$RAW_PROFILE_SUBDIR"
HOST_BUILD_DIR="$OUTPUT_DIR/$HOST_BUILD_SUBDIR"
PROFILE_PATH="$OUTPUT_DIR/$PROFILE_NAME"
METADATA_PATH="$OUTPUT_DIR/BUILD-METADATA.txt"
HOST_PROFILE_RUNTIME_PATH=$(resolve_host_profile_runtime_path)

rm -rf "$OUTPUT_DIR"
mkdir -p "$RAW_PROFILE_DIR"

NDK_RESOURCE_DIR=$(resolve_ndk_resource_dir)
RESOURCE_DIR_SHIM=$(prepare_resource_dir_shim "$NDK_RESOURCE_DIR" "$HOST_PROFILE_RUNTIME_PATH")

CC="$CLANG_BIN" \
CFLAGS="-O2 -fprofile-generate=$RAW_PROFILE_DIR -fprofile-update=atomic -resource-dir=$RESOURCE_DIR_SHIM" \
LDFLAGS="-fprofile-generate=$RAW_PROFILE_DIR -resource-dir=$RESOURCE_DIR_SHIM" \
HOST_WORKLOAD_BUILD_DIR="$HOST_BUILD_DIR" \
HOST_WORKLOAD_OUTPUT_NAME="r47-workload-regression-pgo" \
PROGRAM_ROOT="$PROGRAM_ROOT" \
bash "$SCRIPT_DIR/run_workload_regressions.sh"

"$LLVM_PROFDATA_BIN" merge \
    --output="$PROFILE_PATH" \
    "$RAW_PROFILE_DIR"

cat > "$METADATA_PATH" <<EOF
collector=host-workload-regression
profile_path=$PROFILE_PATH
program_root=$PROGRAM_ROOT
ndk_root=$NDK_ROOT
clang=$CLANG_BIN
llvm_profdata=$LLVM_PROFDATA_BIN
ndk_resource_dir=$NDK_RESOURCE_DIR
resource_dir_shim=$RESOURCE_DIR_SHIM
host_profile_runtime=$HOST_PROFILE_RUNTIME_PATH
EOF

printf '%s\n' "$PROFILE_PATH"
