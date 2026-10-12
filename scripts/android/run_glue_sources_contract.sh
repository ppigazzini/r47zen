#!/bin/bash

# Contract: android/app/src/main/cpp/r47zen/glue_sources.txt is the one list of
# the native glue every build compiles. The app's CMakeLists, the fixture
# exporter, and the host harnesses read it; before it, seven builds carried
# their own copy, so a file added to the glue had to be added seven times.
#
# - Every .c file in the glue directory is listed, except the program-load
#   test bridge, which only an instrumentation build compiles; every listed file
#   exists; none is listed twice.
# - No tracked build file names three or more listed sources by path: that is a
#   hand-kept copy of the list. A script that reads one glue file to check it
#   is not a build list.
#
# The checker proves it fails on a seeded copy first. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

GLUE_DIR="$PROJECT_ROOT/android/app/src/main/cpp/r47zen"
LIST="$GLUE_DIR/glue_sources.txt"
[ -f "$LIST" ] || contract_fail "missing $LIST"

listed="$(sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$LIST")"
[ -n "$listed" ] || contract_fail "glue_sources.txt names no file."

duplicates="$(sort <<<"$listed" | uniq -d)"
[ -z "$duplicates" ] || contract_fail "glue_sources.txt lists a file twice:" "$duplicates"

missing_files=""
while read -r source; do
    [ -f "$GLUE_DIR/$source" ] || missing_files="${missing_files}${source}"$'\n'
done <<<"$listed"
[ -z "$missing_files" ] || contract_fail "glue_sources.txt lists files that do not exist:" "$missing_files"

on_disk="$(cd "$GLUE_DIR" && find . -name '*.c' ! -name 'jni_program_load_test.c' | sed 's|^\./||' | LC_ALL=C sort)"
unlisted="$(LC_ALL=C comm -23 <(printf '%s\n' "$on_disk") <(LC_ALL=C sort <<<"$listed"))"
[ -z "$unlisted" ] || contract_fail "glue sources no build compiles; add them to glue_sources.txt:" "$unlisted"

# Print each FILE that names three or more of the listed sources as r47zen/<path>.
hand_lists() {
    local file count
    for file in "$@"; do
        count=0
        while read -r source; do
            if grep -qF "r47zen/$source" "$file"; then
                count=$((count + 1))
            fi
        done <<<"$listed"
        if [ "$count" -ge 3 ]; then
            printf '%s (%d glue sources)\n' "${file#"$PROJECT_ROOT"/}" "$count"
        fi
    done
}

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
{
    echo 'SOURCES=('
    sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' -e 's|^|    "$TRACKED_CPP_DIR/r47zen/|' -e 's|$|"|' "$LIST"
    echo ')'
} >"$fixture_dir/seeded.sh"
[ -n "$(hand_lists "$fixture_dir/seeded.sh")" ] ||
    contract_fail "the hand-list checker missed a seeded copy of the list."

mapfile -t build_files < <(
    cd "$PROJECT_ROOT" &&
        git ls-files -- 'scripts/*.sh' 'scripts/*CMakeLists.txt' 'scripts/*.cmake' \
            'android/app/src/main/cpp/CMakeLists.txt' '.github/workflows/*.yml' |
        sed "s|^|$PROJECT_ROOT/|"
)
[ "${#build_files[@]}" -gt 0 ] || contract_fail "no tracked build file found."
copies="$(hand_lists "${build_files[@]}")"
[ -z "$copies" ] || contract_fail "a build lists the glue by hand; read glue_sources.txt instead:" "$copies"

contract_pass "glue_sources.txt lists all $(grep -c . <<<"$listed") glue sources, and no build keeps its own copy (${#build_files[@]} files scanned)."
