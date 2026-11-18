#!/bin/bash
#
# Render the calculator screens the Pages site shows, one SVG per scene, from the
# upstream core hydrated at the repo root: stage the native inputs, build
# site_screens.c against the staged core and the Android glue on the host, and run
# each scene scenes.txt lists.
#
# Usage: render_site_screens.sh <output-dir>

set -Eeuo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <output-dir>" >&2
    exit 2
fi
output_dir="$1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/site-screens"

mapfile -t SCENES < <(grep -Ev '^[[:space:]]*(#|$)' "$SCRIPT_DIR/scenes.txt")

env -u CC -u CPPFLAGS -u CFLAGS -u LDFLAGS \
    R47_ANDROID_STAGED_CPP_DIR="$STAGED_CPP_DIR" \
    bash "$PROJECT_ROOT/scripts/android/prepare_native_build_inputs.sh" >&2

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/objects" "$output_dir"

JDK_HOME="$(resolve_jdk_home)"

# The core files upstream compiles, from its own list (r47_core_c47_sources).
c47_list="$(r47_core_c47_sources "$STAGED_CPP_DIR/c47")"
mapfile -t STAGED_C47_SOURCES <<<"$c47_list"
mapfile -t STAGED_GENERATED_SOURCES < <(find "$STAGED_CPP_DIR/generated" -type f -name '*.c' | LC_ALL=C sort)
mapfile -t STAGED_GMP_SOURCES < <(find "$STAGED_CPP_DIR/gmp" -type f -name '*.c' | LC_ALL=C sort)
STAGED_DEC_SOURCES=(
    "$STAGED_CPP_DIR/decNumberICU/decContext.c"
    "$STAGED_CPP_DIR/decNumberICU/decDouble.c"
    "$STAGED_CPP_DIR/decNumberICU/decNumber.c"
    "$STAGED_CPP_DIR/decNumberICU/decPacked.c"
    "$STAGED_CPP_DIR/decNumberICU/decQuad.c"
    "$STAGED_CPP_DIR/decNumberICU/decimal128.c"
    "$STAGED_CPP_DIR/decNumberICU/decimal32.c"
    "$STAGED_CPP_DIR/decNumberICU/decimal64.c"
)
mapfile -t ANDROID_BRIDGE_SOURCES < <(r47_glue_sources "$TRACKED_CPP_DIR")
[ "${#ANDROID_BRIDGE_SOURCES[@]}" -gt 0 ] || exit 1

COMPILE_FLAGS=(-std=gnu17 -O1 -pthread
    -D_GNU_SOURCE -D_DEFAULT_SOURCE
    -DANDROID_BUILD -DHOST_TOOL_BUILD -DPC_BUILD -DLINUX -DOS64BIT -DCALCMODEL=USER_R47
    -I"$JDK_HOME/include"
    -I"$JDK_HOME/include/linux"
    -I"$TRACKED_CPP_DIR/r47zen/stubs"
    -I"$STAGED_CPP_DIR/c47"
    -I"$STAGED_CPP_DIR/c47/core"
    -I"$STAGED_CPP_DIR/c47/hal"
    -I"$STAGED_CPP_DIR/c47/ui"
    -I"$STAGED_CPP_DIR/c47/logicalOps"
    -I"$STAGED_CPP_DIR/c47/mathematics"
    -I"$STAGED_CPP_DIR/c47/programming"
    -I"$STAGED_CPP_DIR/c47/solver"
    -I"$STAGED_CPP_DIR/c47/browsers"
    -I"$STAGED_CPP_DIR/c47/distributions"
    -I"$STAGED_CPP_DIR/c47/c47Extensions"
    -I"$STAGED_CPP_DIR/decNumberICU"
    -I"$STAGED_CPP_DIR/generated"
    -I"$TRACKED_CPP_DIR/r47zen"
    -I"$STAGED_CPP_DIR/gmp"
    -I"$SCRIPT_DIR"
    -include "$TRACKED_CPP_DIR/r47zen/android_mocks.h")

# One object per source, numbered so that two files of the same name cannot collide,
# compiled in parallel; then one link.
SOURCES=(
    "$SCRIPT_DIR/site_screens.c"
    "$SCRIPT_DIR/site_screen_svg.c"
    "${STAGED_C47_SOURCES[@]}"
    "${STAGED_GENERATED_SOURCES[@]}"
    "${STAGED_GMP_SOURCES[@]}"
    "${STAGED_DEC_SOURCES[@]}"
    "${ANDROID_BRIDGE_SOURCES[@]}"
)
for index in "${!SOURCES[@]}"; do
    printf '%s\0%s\0' "$BUILD_DIR/objects/$index.o" "${SOURCES[$index]}"
done | xargs -0 -n 2 -P "$(detect_job_count)" cc "${COMPILE_FLAGS[@]}" -c -o
cc -pthread "$BUILD_DIR"/objects/*.o -lm -o "$BUILD_DIR/r47-site-screens"

# The core prints debug lines to stdout as it draws; keep them out of the log.
for scene in "${SCENES[@]}"; do
    R47_SITE_SCREENS_PROGRAMS="$PROJECT_ROOT/res/PROGRAMS" \
        timeout 60s "$BUILD_DIR/r47-site-screens" "$scene" "$output_dir/$scene.svg" >/dev/null
    echo "Rendered $scene.svg ($(wc -c <"$output_dir/$scene.svg") bytes)." >&2
done
