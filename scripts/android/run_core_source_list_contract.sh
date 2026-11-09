#!/bin/bash
# Contract: the app and every harness compile the core's C files upstream lists,
# not every C file in src/c47.
#
# src/c47 holds a file upstream does not compile, reservedRegisterLookupGenerator.c,
# a developer tool with its own main(). The app took its sources from a glob, so
# that tool went into the shipped library, which then exported `main`; the five
# harness builds each excluded it by name, a second and third owner of the list.
# r47_core_c47_sources in scripts/lib/common.sh now reads upstream's own c47_src
# from src/c47/meson.build. This feeds it a fixture tree: it prints the listed
# files and not an unlisted one, and fails on a listed file that is missing or a
# meson.build with no c47_src. Then no consumer globs src/c47 for *.c any more,
# and the CMake list generator uses the helper. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

c47="$work/c47"
mkdir -p "$c47/core"
cat >"$c47/meson.build" <<'EOF'
c47_src = files(
  'assign.c',
  'core/freeList.c',
)

other = files('assign.c', 'tool.c')
EOF
for file in assign.c core/freeList.c tool.c; do
    echo "int x;" >"$c47/$file"
done

listed="$(r47_core_c47_sources "$c47")" ||
    contract_fail "r47_core_c47_sources refused a complete tree"
[ "$listed" = "$(printf '%s\n' "$c47/assign.c" "$c47/core/freeList.c")" ] ||
    contract_fail "r47_core_c47_sources did not print exactly the listed files:" "$listed"

rm "$c47/core/freeList.c"
if r47_core_c47_sources "$c47" >/dev/null 2>&1; then
    contract_fail "r47_core_c47_sources passed a tree missing a listed file"
fi
printf 'other = files()\n' >"$c47/meson.build"
if r47_core_c47_sources "$c47" >/dev/null 2>&1; then
    contract_fail "r47_core_c47_sources passed a meson.build with no c47_src"
fi

globs="$(scripts_grep "find[^|]*c47[^|]*-name[[:space:]]+'\*\.c'" "$(basename "${BASH_SOURCE[0]}")" || true)"
[ -z "$globs" ] || contract_fail "a script still globs src/c47 for its C sources:" "$globs"
generator="$(strip_hash_comments <"$SCRIPT_DIR/generate_staged_native_metadata.sh")"
grep -q 'r47_core_c47_sources' <<<"$generator" ||
    contract_fail "generate_staged_native_metadata.sh does not take the core's sources from upstream's list"

contract_pass "the app and the harnesses compile the core files upstream's c47_src lists, and no others."
