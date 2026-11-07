#!/bin/bash
# Save a state file with the core and the glue of a past release, so a later core's
# restore can be held to the files phones already carry. It checks the release's overlay
# out in a temporary worktree, hydrates the upstream commit that release shipped, and
# builds that tree's host harness with a driver in place of its main. The driver loads
# FactIt.p47, puts 1, 2, 3, 4 on the stack, and saves. The output is gzipped.
#
# Usage: make_state_fixture.sh <overlay commit> <upstream commit> <output .cfg.gz>
# A release's notes name both commits on their "Sync to commit" and "Build Android
# overlay commit" lines.
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

[ "$#" -eq 3 ] || {
    echo "usage: $0 <overlay commit> <upstream commit> <output .cfg.gz>" >&2
    exit 2
}
overlay="$1"
upstream="$2"
output="$(realpath -m "$3")"

work="$(mktemp -d)"
tree="$work/tree"
cleanup() {
    git -C "$PROJECT_ROOT" worktree remove --force "$tree" 2>/dev/null || true
    rm -rf "$work"
}
trap cleanup EXIT

git -C "$PROJECT_ROOT" worktree add --detach "$tree" "$overlay" >/dev/null
(
    cd "$tree"
    bash ./scripts/upstream-sync/upstream.sh sync --locked --commit "$upstream" >/dev/null
    bash ./scripts/android/prepare_native_build_inputs.sh >/dev/null
)

cat >"$work/driver.c" <<'C'
#include "c47.h"

extern void r47_native_preinit_path(const char *path);
extern void r47_init_runtime(int slotId);
// The glue before the calculator thread had to be set up first, as its harnesses did.
extern void r47_initialize_native_bridge_state(void) __attribute__((weak));
extern void fnLoadProgram(uint16_t unusedButMandatoryParameter);

int main(int argc, char **argv) {
  if (argc != 2) {
    return 2;
  }
  if (r47_initialize_native_bridge_state) {
    r47_initialize_native_bridge_state();
  }
  r47_native_preinit_path(argv[1]);
  r47_init_runtime(0);
  dynamicMenuItem = -1; // as a key would, so the load activates the program
  fnLoadProgram(NOPARAM);
  convertDoubleToReal34Register(1, REGISTER_T);
  convertDoubleToReal34Register(2, REGISTER_Z);
  convertDoubleToReal34Register(3, REGISTER_Y);
  convertDoubleToReal34Register(4, REGISTER_X);
  saveCalc();
  return findNamedLabel("FactIt", ALL_LABELS) != INVALID_VARIABLE ? 0 : 1;
}
C

# That tree's harness build, compiling the driver in place of its harness, with no
# sanitizer and nothing run after the link.
# The copy sits beside the original, which finds its helpers from its own directory.
build="$tree/scripts/workload-regressions/build_clipboard_overflow_harness.sh"
driver_build="$tree/scripts/workload-regressions/build_state_fixture_driver.sh"
sed -e "s|\"\$SCRIPT_DIR/clipboard_overflow_harness.c\"|\"$work/driver.c\"|" \
    -e 's|-fsanitize=address,undefined -fno-sanitize=alignment||' \
    -e '/^echo "Built:/,$d' "$build" >"$driver_build"
built="$(cat "$driver_build")"
[[ "$built" == *"$work/driver.c"* ]] || {
    echo "ERROR: $build no longer names clipboard_overflow_harness.c where this expects" >&2
    exit 1
}
bash "$driver_build" >/dev/null

runtime="$work/runtime"
mkdir -p "$runtime/PROGRAMS"
cp "$tree/res/PROGRAMS/FactIt.p47" "$runtime/PROGRAMS/program.p47"
"$tree/android/build/clipboard-overflow-harness/clipboard-overflow-harness" "$runtime" >/dev/null
gzip -9n <"$runtime/backupR47_0.cfg" >"$output"
echo "Saved $output: overlay $overlay, upstream $upstream"
