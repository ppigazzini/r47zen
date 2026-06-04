#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
ANDROID_DIR="$PROJECT_ROOT/android"
DEFAULTS_FILE="$ANDROID_DIR/r47-defaults.properties"
LOCAL_PROPERTIES_FILE="$ANDROID_DIR/local.properties"
TESTSUITE_TESTS_DIR="$PROJECT_ROOT/src/testSuite/tests"
DEFAULT_OUTPUT_DIR="$ANDROID_DIR/build/host-pgo"
PROFILE_NAME="r47-host-core.profdata"
RAW_PROFILE_SUBDIR="raw"
HOST_BUILD_SUBDIR="build"
PROGRAM_FIXTURE_BUILD_SUBDIR="workload-build"
PROGRAM_FIXTURE_STAGE_SUBDIR="program-fixtures"
RESOURCE_SHIM_SUBDIR="resource-dir"
INPUT_SUBDIR="inputs"
LOG_SUBDIR="logs"
RUNTIME_SUBDIR="runtime"
MATRIX_WORKLOAD_SOURCE="$TESTSUITE_TESTS_DIR/matrix.txt"
DEFAULT_TRAINING_WORKLOAD="broad-ci"
MESON_BUILD_TYPE="release"
EXPECTED_RELEASE_OPT_FLAG="-O3"
PROGRAM_FIXTURE_OUTPUT_NAME="r47-workload-regression-pgo"
PROGRAM_FIXTURE_STAGING_SCRIPT="$PROJECT_ROOT/scripts/android/stage_program_fixture_assets.sh"
PROGRAM_FIXTURE_GENERATED_ROOT="$PROJECT_ROOT/android/app/build/generated/assets/runtime/program-fixtures/PROGRAMS"
PROGRAM_FIXTURE_UPSTREAM_ROOT="$PROJECT_ROOT/res/PROGRAMS"

OUTPUT_DIR="$DEFAULT_OUTPUT_DIR"
TRAINING_WORKLOAD_RAW="${R47_HOST_PGO_TRAINING_WORKLOAD:-$DEFAULT_TRAINING_WORKLOAD}"
PROGRAM_ROOT_OVERRIDE="${PROGRAM_ROOT:-}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
HOST_PROFILE_RUNTIME_PATH="${R47_HOST_PROFILE_RUNTIME_PATH:-}"

usage() {
    cat <<'EOF'
Usage: scripts/workload-regressions/collect_host_pgo_profile.sh [--output-dir <dir>] [--ndk-root <dir>] [--training-workload <broad-ci|matrix|matrix-prefix-N>] [--program-root <dir>]

Builds the upstream testSuite target with the pinned NDK Clang and llvm-profdata
pair, collects raw LLVM IRPGO profiles from a representative multi-family
workload, and merges them into an indexed .profdata file that can be fed back
into the Android Release native build.

The maintained CI default is the broad-ci corpus: programs, tvm,
jacobi_audit, normal_i, gamma, trig, prime, factorial, and an
upstream-derived matrix-prefix-85 slice. REPORT-17 identified this exact
family mix as the smallest broad testSuite corpus that reaches well beyond the
old PROGRAMS harness while keeping matrix coverage and staying far leaner than
full-matrix training.

The Android NDK does not ship the host Linux profiling runtime archive needed
to link -fprofile-generate binaries. This script therefore synthesizes a
temporary Clang resource-dir shim that reuses a host-installed
libclang_rt.profile archive. Override that archive explicitly with
R47_HOST_PROFILE_RUNTIME_PATH when needed.

Supported --training-workload values:

- broad-ci
- matrix
- matrix-prefix-N

--program-root is used only for the program-fixture overlay collected through
run_workload_regressions.sh. It does not affect the upstream testSuite files.
When the overlay is enabled and no explicit program root is supplied, the
collector prefers generated Android fixture assets, then a synced workspace
PROGRAMS tree, and finally stages canonical PROGRAMS fixtures into its own
output directory.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

warn() {
    echo "WARNING: $*" >&2
}

absolutize_path() {
    case "$1" in
        /*)
            printf '%s\n' "$1"
            ;;
        *)
            printf '%s/%s\n' "$PROJECT_ROOT" "$1"
            ;;
    esac
}

join_with_delimiter() {
    local delimiter="$1"
    shift
    local joined=""
    local element=""

    for element in "$@"; do
        if [[ -z "$joined" ]]; then
            joined="$element"
        else
            joined+="$delimiter$element"
        fi
    done

    printf '%s\n' "$joined"
}

validate_positive_integer() {
    case "$1" in
        ''|*[!0-9]*|0)
            fail "$2 must be a positive integer."
            ;;
    esac
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command '$1' is not available on PATH."
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

ensure_xlsxio_tool() {
    local xlsxio_commit=""
    local cache_bin_dir=""
    local temp_bin_dir=""

    if command -v xlsxio_xlsx2csv >/dev/null 2>&1; then
        return 0
    fi

    xlsxio_commit=$(read_default_value R47_DEFAULT_XLSXIO_COMMIT)
    cache_bin_dir="$HOME/.cache/r47/xlsxio/$xlsxio_commit/bin"

    if [ -x "$cache_bin_dir/xlsxio_xlsx2csv" ]; then
        export PATH="$cache_bin_dir:$PATH"
    fi

    temp_bin_dir="${TMPDIR:-}/bin"
    if [ -n "${TMPDIR:-}" ] && [ -x "$temp_bin_dir/xlsxio_xlsx2csv" ]; then
        export PATH="$temp_bin_dir:$PATH"
    fi

    command -v xlsxio_xlsx2csv >/dev/null 2>&1 || fail "xlsxio_xlsx2csv is required on PATH before collecting host PGO profiles."
}

resolve_llvm_tool() {
    local tool_name="$1"
    local ndk_root="$2"
    local tool_path="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/$tool_name"

    [[ -x "$tool_path" ]] || fail "Missing LLVM tool $tool_path"
    printf '%s\n' "$tool_path"
}

resolve_ndk_llvm_major_version() {
    local version_text=""

    version_text="$($CLANG_BIN --version | head -n1)"
    sed -nE 's/.*clang version ([0-9]+).*/\1/p' <<< "$version_text"
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
    local llvm_major="$1"
    local runtime_suffix=""
    local runtime_path=""
    local resource_dir=""
    local versioned_clang=""

    if [[ -n "$HOST_PROFILE_RUNTIME_PATH" ]]; then
        [[ -f "$HOST_PROFILE_RUNTIME_PATH" ]] || fail "Host profile runtime archive $HOST_PROFILE_RUNTIME_PATH does not exist."
        printf '%s\n' "$HOST_PROFILE_RUNTIME_PATH"
        return 0
    fi

    runtime_suffix=$(resolve_host_profile_runtime_suffix)

    [[ -n "$llvm_major" ]] || fail "Missing LLVM major version for host profile runtime lookup."

    versioned_clang="$(command -v "clang-$llvm_major" 2>/dev/null || true)"
    if [[ -n "$versioned_clang" ]]; then
        resource_dir="$($versioned_clang --print-resource-dir 2>/dev/null || true)"
    elif command -v clang >/dev/null 2>&1; then
        resource_dir="$(clang --print-resource-dir 2>/dev/null || true)"
        if [[ "$resource_dir" != *"/$llvm_major"* ]]; then
            resource_dir=""
        fi
    fi

    if [[ -n "$resource_dir" ]]; then
        runtime_path="$resource_dir/lib/linux/libclang_rt.profile-${runtime_suffix}.a"
        if [[ -f "$runtime_path" ]]; then
            printf '%s\n' "$runtime_path"
            return 0
        fi
    fi

    runtime_path="$({ find /usr/lib -path "*/lib/clang/${llvm_major}*/lib/linux/libclang_rt.profile-${runtime_suffix}.a" 2>/dev/null || true; } | LC_ALL=C sort -V | tail -n 1)"
    [[ -n "$runtime_path" ]] || fail "Could not locate a host LLVM profiling runtime archive matching LLVM ${llvm_major} for ${runtime_suffix}. Install clang-${llvm_major} and libclang-rt-${llvm_major}-dev, or set R47_HOST_PROFILE_RUNTIME_PATH explicitly."
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

# shellcheck disable=SC2034  # workload_*_ref are nameref out-params; writes update the caller's variables
resolve_training_workload_descriptor() {
    local raw_workload="$1"
    local -n workload_id_ref="$2"
    local -n workload_name_ref="$3"
    local -n workload_limit_ref="$4"
    local derived_limit=""

    case "$raw_workload" in
        broad-ci)
            workload_id_ref="broad-ci"
            workload_name_ref="broad_ci"
            workload_limit_ref=""
            ;;
        matrix)
            workload_id_ref="matrix"
            workload_name_ref="matrix"
            workload_limit_ref=""
            ;;
        matrix-prefix-*)
            derived_limit="${raw_workload#matrix-prefix-}"
            validate_positive_integer "$derived_limit" "matrix-prefix workload limit"
            workload_id_ref="matrix-prefix-$derived_limit"
            workload_name_ref="matrix_prefix_$derived_limit"
            workload_limit_ref="$derived_limit"
            ;;
        *)
            fail "Unknown training workload '$raw_workload'. Use broad-ci, matrix, or matrix-prefix-N."
            ;;
    esac
}

stage_upstream_test_input() {
    local input_name="$1"
    local source_path="$TESTSUITE_TESTS_DIR/$input_name.txt"
    local staged_path="$INPUT_ROOT/$input_name.txt"

    [ -f "$source_path" ] || fail "Missing upstream testSuite input at $source_path"
    ln -sfn "$source_path" "$staged_path"
}

stage_matrix_prefix_input() {
    local output_name="$1"
    local out_limit="$2"
    local staged_path="$INPUT_ROOT/$output_name.txt"

    [ -f "$MATRIX_WORKLOAD_SOURCE" ] || fail "Missing matrix workload source at $MATRIX_WORKLOAD_SOURCE"

    python3 - "$MATRIX_WORKLOAD_SOURCE" "$staged_path" "$out_limit" <<'PY'
from pathlib import Path
import sys

src_path = Path(sys.argv[1])
dst_path = Path(sys.argv[2])
limit = int(sys.argv[3])

out_count = 0
kept = []
for raw in src_path.read_text(encoding="utf-8", errors="replace").splitlines(True):
    kept.append(raw)
    if raw.lstrip().startswith("Out:"):
        out_count += 1
        if out_count == limit:
            break

if out_count < limit:
    sys.stderr.write(
        f"matrix slice requested {limit} Out: cases but found only {out_count}\n"
    )
    sys.exit(1)

dst_path.write_text("".join(kept), encoding="utf-8")
PY

    GENERATED_TRAINING_INPUTS+=("$output_name")
}

prepare_training_input() {
    WORKLOAD_LIST_PATH="$INPUT_ROOT/${TRAINING_WORKLOAD_NAME}_list.txt"
    TRAINING_INPUTS_DIR="$INPUT_ROOT"
    TRAINING_WORKLOAD_ENTRIES=()
    GENERATED_TRAINING_INPUTS=()
    TRAINING_WORKLOAD_ENTRIES_CSV=""
    TRAINING_GENERATED_INPUTS_CSV=""

    mkdir -p "$INPUT_ROOT"
    rm -f "$WORKLOAD_LIST_PATH"
    find "$INPUT_ROOT" -mindepth 1 -maxdepth 1 -type l -delete
    find "$INPUT_ROOT" -mindepth 1 -maxdepth 1 -type f -name '*.txt' -delete

    case "$TRAINING_WORKLOAD_ID" in
        broad-ci)
            TRAINING_WORKLOAD_ENTRIES=(
                programs
                tvm
                jacobi_audit
                normal_i
                gamma
                trig
                prime
                factorial
                matrix_prefix_85
            )

            stage_upstream_test_input programs
            stage_upstream_test_input tvm
            stage_upstream_test_input jacobi_audit
            stage_upstream_test_input normal_i
            stage_upstream_test_input gamma
            stage_upstream_test_input trig
            stage_upstream_test_input prime
            stage_upstream_test_input factorial
            stage_matrix_prefix_input matrix_prefix_85 85
            ;;
        matrix)
            TRAINING_WORKLOAD_ENTRIES=(matrix)
            stage_upstream_test_input matrix
            ;;
        matrix-prefix-*)
            TRAINING_WORKLOAD_ENTRIES=("$TRAINING_WORKLOAD_NAME")
            stage_matrix_prefix_input "$TRAINING_WORKLOAD_NAME" "$TRAINING_WORKLOAD_LIMIT"
            ;;
        *)
            fail "Unsupported training workload id '$TRAINING_WORKLOAD_ID' while preparing inputs."
            ;;
    esac

    printf '%s\n' "${TRAINING_WORKLOAD_ENTRIES[@]}" > "$WORKLOAD_LIST_PATH"
    TRAINING_WORKLOAD_ENTRIES_CSV=$(join_with_delimiter ',' "${TRAINING_WORKLOAD_ENTRIES[@]}")
    TRAINING_GENERATED_INPUTS_CSV=$(join_with_delimiter ',' "${GENERATED_TRAINING_INPUTS[@]}")
}

training_workload_requires_testpgms() {
    local entry=""

    for entry in "${TRAINING_WORKLOAD_ENTRIES[@]}"; do
        if [[ "$entry" == "programs" ]]; then
            return 0
        fi
    done

    return 1
}

ensure_runtime_root() {
    local entry=""
    local base_name=""

    rm -rf "$RUNTIME_ROOT/res"
    mkdir -p "$RUNTIME_ROOT/res"

    if [[ -d "$PROJECT_ROOT/res" ]]; then
        while IFS= read -r entry; do
            base_name="$(basename "$entry")"
            if [[ "$base_name" == "testPgms" ]]; then
                continue
            fi
            ln -sfn "$entry" "$RUNTIME_ROOT/res/$base_name"
        done < <(find "$PROJECT_ROOT/res" -mindepth 1 -maxdepth 1 -print | LC_ALL=C sort)
    fi
}

prepare_program_workload_runtime() {
    local testpgms_path="$HOST_BUILD_DIR/src/generateTestPgms/testPgms.bin"

    ensure_runtime_root
    ninja -C "$HOST_BUILD_DIR" -j "$JOBS" testPgms >&2

    mkdir -p "$RUNTIME_ROOT/res/testPgms"
    ln -sfn "$testpgms_path" "$RUNTIME_ROOT/res/testPgms/testPgms.bin"
}

resolve_training_runtime_cwd() {
    if training_workload_requires_testpgms; then
        prepare_program_workload_runtime
        printf '%s\n' "$RUNTIME_ROOT"
        return 0
    fi

    printf '%s\n' "$PROJECT_ROOT"
}

should_collect_program_fixture_overlay() {
    local requested_state="${R47_HOST_PGO_INCLUDE_PROGRAM_FIXTURES:-}"

    if [[ -n "$requested_state" ]]; then
        case "$requested_state" in
            1|true|TRUE|yes|YES|on|ON)
                return 0
                ;;
            0|false|FALSE|no|NO|off|OFF)
                return 1
                ;;
            *)
                fail "R47_HOST_PGO_INCLUDE_PROGRAM_FIXTURES must be a boolean when set."
                ;;
        esac
    fi

    [[ "$TRAINING_WORKLOAD_ID" == "broad-ci" ]]
}

# shellcheck disable=SC2034  # out_*_ref are nameref out-params; writes update the caller's variables
resolve_program_fixture_overlay_root() {
    local -n out_root_ref="$1"
    local -n out_source_ref="$2"
    local staged_root="$OUTPUT_DIR/$PROGRAM_FIXTURE_STAGE_SUBDIR"

    if [[ -n "$PROGRAM_ROOT_OVERRIDE" ]]; then
        out_root_ref="$PROGRAM_ROOT_OVERRIDE"
        out_source_ref="override"
        return 0
    fi

    if [[ -d "$PROGRAM_FIXTURE_GENERATED_ROOT" ]]; then
        out_root_ref="$PROGRAM_FIXTURE_GENERATED_ROOT"
        out_source_ref="generated-assets"
        return 0
    fi

    if [[ -d "$PROGRAM_FIXTURE_UPSTREAM_ROOT" ]]; then
        out_root_ref="$PROGRAM_FIXTURE_UPSTREAM_ROOT"
        out_source_ref="workspace-upstream"
        return 0
    fi

    warn "Program fixture root is not already staged; collecting canonical PROGRAMS fixtures into $staged_root"
    bash "$PROGRAM_FIXTURE_STAGING_SCRIPT" --output-dir "$staged_root" >&2

    [[ -d "$staged_root/PROGRAMS" ]] || \
        fail "Program fixture staging did not produce $staged_root/PROGRAMS"

    out_root_ref="$staged_root/PROGRAMS"
    out_source_ref="collector-staged"
}

resolve_program_fixture_runtime_cwd() {
    prepare_program_workload_runtime
    printf '%s\n' "$RUNTIME_ROOT"
}

run_program_fixture_profile_overlay() {
    local runtime_cwd="$1"

    mkdir -p "$(dirname "$PROGRAM_FIXTURE_LOG_PATH")"

    python3 - "$PROGRAM_FIXTURE_LOG_PATH" "$runtime_cwd" "$SCRIPT_DIR/run_workload_regressions.sh" <<'PY'
import os
import subprocess
import sys
import time

log_path, runtime_cwd, script_path = sys.argv[1:4]
command = ["bash", script_path]
start = time.perf_counter()
with open(log_path, "wb") as log_file:
    proc = subprocess.run(
        command,
        cwd=runtime_cwd,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        env=os.environ.copy(),
    )
elapsed = time.perf_counter() - start
if proc.returncode != 0:
    sys.stderr.write(f"program-fixture overlay exited with {proc.returncode}; see {log_path}\n")
    sys.exit(proc.returncode)
print(f"{elapsed:.6f}")
PY
}

validate_effective_cflags() {
    local build_dir="$1"
    local required_cflags="$2"
    local compile_commands_path="$build_dir/compile_commands.json"

    [ -f "$compile_commands_path" ] || fail "Missing compile_commands.json in $build_dir"

    python3 - "$compile_commands_path" "$EXPECTED_RELEASE_OPT_FLAG" "$required_cflags" <<'PY'
import json
import shlex
import sys
from pathlib import Path

compile_commands_path = Path(sys.argv[1])
required_opt_flag = sys.argv[2]
required_cflags = [flag for flag in shlex.split(sys.argv[3]) if flag]
commands = json.loads(compile_commands_path.read_text(encoding="utf-8"))

if not commands:
    raise SystemExit(f"No compile commands found in {compile_commands_path}")

for entry in commands:
    argv = entry.get("arguments")
    if argv is None:
        command = entry.get("command")
        if not command:
            raise SystemExit(
                f"Compile command entry missing both 'arguments' and 'command' in {compile_commands_path}"
            )
        argv = shlex.split(command)

    missing = []
    if required_opt_flag not in argv:
        missing.append(required_opt_flag)

    for flag in required_cflags:
        if flag not in argv:
            missing.append(flag)

    if missing:
        file_path = entry.get("file", "<unknown>")
        missing_flags = ", ".join(missing)
        raise SystemExit(
            f"Effective clang flags missing {missing_flags} for {file_path} in {compile_commands_path}"
        )
PY
}

configure_instrumented_build() {
    require_command meson
    require_command ninja
    ensure_xlsxio_tool

    rm -rf "$HOST_BUILD_DIR"

    INSTRUMENT_CFLAGS="-flto=thin -fprofile-generate=$RAW_PROFILE_DIR -fprofile-update=atomic -resource-dir=$RESOURCE_DIR_SHIM"
    INSTRUMENT_LDFLAGS="-flto=thin -fuse-ld=lld -fprofile-generate=$RAW_PROFILE_DIR -resource-dir=$RESOURCE_DIR_SHIM"

    env \
        -u CC \
        -u AR \
        -u RANLIB \
        -u CPPFLAGS \
        -u CFLAGS \
        -u LDFLAGS \
        CC="$CLANG_BIN" \
        AR="$LLVM_AR_BIN" \
        RANLIB="$LLVM_RANLIB_BIN" \
        CFLAGS="$INSTRUMENT_CFLAGS" \
        LDFLAGS="$INSTRUMENT_LDFLAGS" \
        meson setup "$HOST_BUILD_DIR" \
            --buildtype="$MESON_BUILD_TYPE" \
            -DRASPBERRY=false \
            -DDECNUMBER_FASTMUL=true

    validate_effective_cflags "$HOST_BUILD_DIR" "-flto=thin -fprofile-generate=$RAW_PROFILE_DIR"

    ninja -C "$HOST_BUILD_DIR" -j "$JOBS" src/c47/vcs.h
    ninja -C "$HOST_BUILD_DIR" -j "$JOBS" src/testSuite/testSuite

    INSTRUMENTED_BINARY_PATH="$HOST_BUILD_DIR/src/testSuite/testSuite"
    [ -x "$INSTRUMENTED_BINARY_PATH" ] || fail "Missing instrumented testSuite binary at $INSTRUMENTED_BINARY_PATH"
}

measure_training_run() {
    local binary_path="$1"
    local list_path="$2"
    local log_path="$3"
    local runtime_cwd="$4"

    mkdir -p "$(dirname "$log_path")"

    python3 - "$binary_path" "$list_path" "$log_path" "$runtime_cwd" <<'PY'
import subprocess
import sys
import time

binary_path, list_path, log_path, runtime_cwd = sys.argv[1:5]
start = time.perf_counter()
with open(log_path, "wb") as log_file:
    proc = subprocess.run(
        [binary_path, list_path],
        cwd=runtime_cwd,
        stdout=log_file,
        stderr=subprocess.STDOUT,
    )
elapsed = time.perf_counter() - start
if proc.returncode != 0:
    sys.stderr.write(f"binary exited with {proc.returncode}; see {log_path}\n")
    sys.exit(proc.returncode)
print(f"{elapsed:.6f}")
PY
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
            PROGRAM_ROOT_OVERRIDE="$1"
            ;;
        --ndk-root)
            shift
            [[ "$#" -gt 0 ]] || fail "Missing value for --ndk-root"
            NDK_ROOT="$1"
            ;;
        --training-workload)
            shift
            [[ "$#" -gt 0 ]] || fail "Missing value for --training-workload"
            TRAINING_WORKLOAD_RAW="$1"
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

OUTPUT_DIR=$(absolutize_path "$OUTPUT_DIR")
TESTSUITE_TESTS_DIR=$(absolutize_path "$TESTSUITE_TESTS_DIR")
MATRIX_WORKLOAD_SOURCE=$(absolutize_path "$MATRIX_WORKLOAD_SOURCE")

if [[ -n "$PROGRAM_ROOT_OVERRIDE" ]]; then
    PROGRAM_ROOT_OVERRIDE=$(absolutize_path "$PROGRAM_ROOT_OVERRIDE")
fi

resolve_training_workload_descriptor \
    "$TRAINING_WORKLOAD_RAW" \
    TRAINING_WORKLOAD_ID \
    TRAINING_WORKLOAD_NAME \
    TRAINING_WORKLOAD_LIMIT

NDK_ROOT=$(resolve_android_ndk_root)
CLANG_BIN=$(resolve_llvm_tool clang "$NDK_ROOT")
LLVM_AR_BIN=$(resolve_llvm_tool llvm-ar "$NDK_ROOT")
LLVM_RANLIB_BIN=$(resolve_llvm_tool llvm-ranlib "$NDK_ROOT")
LLVM_PROFDATA_BIN=$(resolve_llvm_tool llvm-profdata "$NDK_ROOT")
NDK_LLVM_MAJOR=$(resolve_ndk_llvm_major_version)
RAW_PROFILE_DIR="$OUTPUT_DIR/$RAW_PROFILE_SUBDIR"
HOST_BUILD_DIR="$OUTPUT_DIR/$HOST_BUILD_SUBDIR"
PROGRAM_FIXTURE_BUILD_DIR="$OUTPUT_DIR/$PROGRAM_FIXTURE_BUILD_SUBDIR"
INPUT_ROOT="$OUTPUT_DIR/$INPUT_SUBDIR"
TRAINING_LOG_PATH="$OUTPUT_DIR/$LOG_SUBDIR/${TRAINING_WORKLOAD_NAME}.log"
PROGRAM_FIXTURE_LOG_PATH="$OUTPUT_DIR/$LOG_SUBDIR/program-fixture-overlay.log"
PROFILE_PATH="$OUTPUT_DIR/$PROFILE_NAME"
METADATA_PATH="$OUTPUT_DIR/BUILD-METADATA.txt"
RUNTIME_ROOT="$OUTPUT_DIR/$RUNTIME_SUBDIR"
JOBS="${R47_BUILD_JOBS:-}"

[[ -n "$NDK_LLVM_MAJOR" ]] || fail "Failed to determine LLVM major version from $CLANG_BIN"

if ! JOBS=$(normalize_job_count "$JOBS"); then
    JOBS=$(detect_job_count)
fi

HOST_PROFILE_RUNTIME_PATH=$(resolve_host_profile_runtime_path "$NDK_LLVM_MAJOR")

rm -rf "$OUTPUT_DIR"
mkdir -p "$RAW_PROFILE_DIR"

prepare_training_input

NDK_RESOURCE_DIR=$(resolve_ndk_resource_dir)
RESOURCE_DIR_SHIM=$(prepare_resource_dir_shim "$NDK_RESOURCE_DIR" "$HOST_PROFILE_RUNTIME_PATH")

configure_instrumented_build

PROGRAM_FIXTURE_CFLAGS="-O3 $INSTRUMENT_CFLAGS"
PROGRAM_FIXTURE_LDFLAGS="$INSTRUMENT_LDFLAGS"

TRAINING_RUNTIME_CWD=$(resolve_training_runtime_cwd)

echo "INFO: Collecting host-core PGO profile from $TRAINING_WORKLOAD_ID" >&2
TRAINING_ELAPSED_SECONDS=$(measure_training_run \
    "$INSTRUMENTED_BINARY_PATH" \
    "$WORKLOAD_LIST_PATH" \
    "$TRAINING_LOG_PATH" \
    "$TRAINING_RUNTIME_CWD")

PROGRAM_FIXTURE_OVERLAY_ENABLED=false
PROGRAM_FIXTURE_OVERLAY_ELAPSED_SECONDS=""
PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT=""
PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT_SOURCE=""
PROGRAM_FIXTURE_OVERLAY_RUNTIME_CWD=""

if should_collect_program_fixture_overlay; then
    PROGRAM_FIXTURE_OVERLAY_ENABLED=true
    resolve_program_fixture_overlay_root \
        PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT \
        PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT_SOURCE
    PROGRAM_FIXTURE_OVERLAY_RUNTIME_CWD=$(resolve_program_fixture_runtime_cwd)
    PROGRAM_FIXTURE_OVERLAY_ELAPSED_SECONDS=$( \
        CC="$CLANG_BIN" \
        CFLAGS="$PROGRAM_FIXTURE_CFLAGS" \
        LDFLAGS="$PROGRAM_FIXTURE_LDFLAGS" \
        HOST_WORKLOAD_BUILD_DIR="$PROGRAM_FIXTURE_BUILD_DIR" \
        HOST_WORKLOAD_OUTPUT_NAME="$PROGRAM_FIXTURE_OUTPUT_NAME" \
        PROGRAM_ROOT="$PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT" \
        run_program_fixture_profile_overlay "$PROGRAM_FIXTURE_OVERLAY_RUNTIME_CWD" \
    )
fi

mapfile -t RAW_PROFILES < <(find "$RAW_PROFILE_DIR" -type f -name '*.profraw' | LC_ALL=C sort)

if [[ ${#RAW_PROFILES[@]} -eq 0 ]]; then
    fail "No raw LLVM profile files were generated in $RAW_PROFILE_DIR. This usually means the host profiling runtime is incompatible with $CLANG_BIN."
fi

"$LLVM_PROFDATA_BIN" merge \
    --output="$PROFILE_PATH" \
    "${RAW_PROFILES[@]}"

cat > "$METADATA_PATH" <<EOF
collector=host-testsuite-pgo
profile_path=$PROFILE_PATH
training_workload=$TRAINING_WORKLOAD_ID
training_workload_name=$TRAINING_WORKLOAD_NAME
training_workload_limit=${TRAINING_WORKLOAD_LIMIT:-}
training_workload_entries=$TRAINING_WORKLOAD_ENTRIES_CSV
training_generated_inputs=$TRAINING_GENERATED_INPUTS_CSV
training_workload_source_dir=$TESTSUITE_TESTS_DIR
training_matrix_workload_source=$MATRIX_WORKLOAD_SOURCE
training_input_dir=$TRAINING_INPUTS_DIR
training_runtime_cwd=$TRAINING_RUNTIME_CWD
training_list_path=$WORKLOAD_LIST_PATH
training_log_path=$TRAINING_LOG_PATH
training_elapsed_seconds=$TRAINING_ELAPSED_SECONDS
program_fixture_overlay_enabled=$PROGRAM_FIXTURE_OVERLAY_ENABLED
program_fixture_overlay_build_dir=$PROGRAM_FIXTURE_BUILD_DIR
program_fixture_overlay_output_name=$PROGRAM_FIXTURE_OUTPUT_NAME
program_fixture_overlay_program_root=${PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT:-}
program_fixture_overlay_program_root_source=${PROGRAM_FIXTURE_OVERLAY_PROGRAM_ROOT_SOURCE:-}
program_fixture_overlay_runtime_cwd=${PROGRAM_FIXTURE_OVERLAY_RUNTIME_CWD:-}
program_fixture_overlay_log_path=$PROGRAM_FIXTURE_LOG_PATH
program_fixture_overlay_elapsed_seconds=${PROGRAM_FIXTURE_OVERLAY_ELAPSED_SECONDS:-}
build_dir=$HOST_BUILD_DIR
build_type=$MESON_BUILD_TYPE
jobs=$JOBS
ndk_root=$NDK_ROOT
ndk_llvm_major=$NDK_LLVM_MAJOR
clang=$CLANG_BIN
llvm_profdata=$LLVM_PROFDATA_BIN
ndk_resource_dir=$NDK_RESOURCE_DIR
resource_dir_shim=$RESOURCE_DIR_SHIM
host_profile_runtime=$HOST_PROFILE_RUNTIME_PATH
instrument_cflags=$INSTRUMENT_CFLAGS
instrument_ldflags=$INSTRUMENT_LDFLAGS
EOF

printf '%s\n' "$PROFILE_PATH"
