#!/bin/bash
# Contract: the app compiles the core in the language the firmware does. Upstream builds
# the shipping calculator with the c_args of src/c47-dmcp/cross_arm_gcc.build and
# src/c47-dmcp5/cross_arm_gcc.build, and the dmcp_cargs of those folders' meson.build.
# arm64 makes a plain char unsigned, and clang's -O3 relies on strict aliasing, so
# without -fsigned-char and -fno-strict-aliasing the app would run code the firmware
# never ran. The PGO collector builds the profile the
# release uses, so it takes them too.
#
# Every -f flag in those lists is either one of LANGUAGE_FLAGS or one that shapes
# code and data layout, not the meaning of the source, listed in CODEGEN_FLAGS. A new
# flag in a cross file fails until someone sorts it. Comments are ignored everywhere,
# and the app's build must not undo a flag with its opposite.
#
# Usage: run_core_language_flags_contract.sh [<src dir>]. With no directory, a clone with
# no hydrated core checks the copies and the reader against a seed. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

LANGUAGE_FLAGS=(-fsigned-char -fno-strict-aliasing)
# Sections, constant merging, and unwind tables for the firmware's flash size, not the
# language.
CODEGEN_FLAGS=(-ffunction-sections -fdata-sections -fmerge-all-constants -fno-exceptions
    -fno-unwind-tables -fno-asynchronous-unwind-tables)
OPPOSITES=(-funsigned-char -fstrict-aliasing)
CMAKE_FILE="$PROJECT_ROOT/android/app/src/main/cpp/CMakeLists.txt"
PGO_SCRIPT="$PROJECT_ROOT/scripts/workload-regressions/collect_host_pgo_profile.sh"

# Print each -f flag of the list named $2 in a meson file, one per line, comments left out.
cross_file_flags() {
    sed 's/#.*//' "$1" |
        awk -v name="${2:-c_args}" '$1 == name && $2 == "=" { inside = 1 } inside { print } inside && /\]/ { exit }' |
        grep -oE "'-f[A-Za-z0-9_=-]+'" | tr -d "'" | LC_ALL=C sort -u || true
}

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
printf "c_args = [\n  '-O2',\n  '-fsigned-char',\n  # '-fwrapv',\n  '-fno-strict-aliasing']\nlink = ['-fpie']\n" \
    >"$fixture/cross.build"
[ "$(cross_file_flags "$fixture/cross.build" | tr '\n' ' ')" = "-fno-strict-aliasing -fsigned-char " ] ||
    contract_fail "the reader did not read its seed: $(cross_file_flags "$fixture/cross.build" | tr '\n' ' ')"

cmake_text="$(sed 's/#.*//' "$CMAKE_FILE")"
cmake_options="$(grep -E '^target_compile_options\(r47zen PRIVATE ' <<<"$cmake_text" || true)"
pgo_text="$(strip_hash_comments <"$PGO_SCRIPT")"
for flag in "${LANGUAGE_FLAGS[@]}"; do
    [[ "$cmake_options" == *"$flag"* ]] ||
        contract_fail "no target_compile_options(r47zen PRIVATE ...) line in CMakeLists.txt compiles the core with $flag."
    [[ "$pgo_text" == *"$flag"* ]] || contract_fail "collect_host_pgo_profile.sh does not build the profile with $flag."
done
for flag in "${OPPOSITES[@]}"; do
    [[ "$cmake_text" != *"$flag"* ]] || contract_fail "CMakeLists.txt undoes a language flag with $flag."
done

SRC_DIR="${1:-$PROJECT_ROOT/src}"
if [ ! -d "$SRC_DIR/c47-dmcp" ]; then
    [ "$#" -eq 0 ] || contract_fail "no firmware cross files under $SRC_DIR."
    contract_pass "no hydrated core: the app and the profile take ${LANGUAGE_FLAGS[*]}."
fi
known="$(printf '%s\n' "${LANGUAGE_FLAGS[@]}" "${CODEGEN_FLAGS[@]}" | LC_ALL=C sort -u)"
for folder in c47-dmcp c47-dmcp5; do
    cross="$SRC_DIR/$folder/cross_arm_gcc.build"
    if [ ! -f "$cross" ] || [ ! -f "$SRC_DIR/$folder/meson.build" ]; then
        contract_fail "no firmware build files in $SRC_DIR/$folder."
    fi
    extra="$(cross_file_flags "$SRC_DIR/$folder/meson.build" dmcp_cargs)"
    unsorted="$(LC_ALL=C comm -23 <(printf '%s\n' "$extra") <(printf '%s\n' "$known"))"
    [ -z "$extra" ] || [ -z "$unsorted" ] ||
        contract_fail "$folder/meson.build has -f flags this contract does not sort; decide whether the app needs them:" "$unsorted"
    firmware="$(cross_file_flags "$cross")"
    unsorted="$(LC_ALL=C comm -23 <(printf '%s\n' "$firmware") <(printf '%s\n' "$known"))"
    [ -z "$unsorted" ] ||
        contract_fail "${cross#"$SRC_DIR"/} has -f flags this contract does not sort; decide whether the app needs them:" "$unsorted"
    for flag in "${LANGUAGE_FLAGS[@]}"; do
        grep -qxF -- "$flag" <<<"$firmware" ||
            contract_fail "${cross#"$SRC_DIR"/} no longer has $flag; read whether the app should keep it."
    done
done

contract_pass "the app and the profile compile the core with the firmware's ${LANGUAGE_FLAGS[*]}."
