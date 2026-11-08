#!/bin/bash
# Export the keypad scenes the JVM tests read: build keypad_fixture_exporter.c on the
# host with the staged core and the glue, then write each scene and a manifest.json that
# names the upstream commit. It needs the staged tree of a full build, and a JDK for
# jni.h.
#
# Usage: export_upstream_keypad_fixtures.sh [--output-dir <dir>]
# The default directory is the tracked test resources;
# report_keypad_fixture_drift.sh exports to a directory of its own.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=scripts/lib/common.sh
source "$SCRIPT_DIR/../lib/common.sh"
STAGED_CPP_DIR="$PROJECT_ROOT/android/.staged-native/cpp"
BUILD_DIR="$PROJECT_ROOT/android/build/keypad-fixtures-host"
OUTPUT_DIR="$PROJECT_ROOT/android/app/src/test/resources/keypad-fixtures"
TRACKED_CPP_DIR="$PROJECT_ROOT/android/app/src/main/cpp"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output-dir)
            [ "$#" -gt 1 ] || {
                echo "ERROR: --output-dir needs a directory." >&2
                exit 2
            }
            OUTPUT_DIR="$2"
            shift 2
            ;;
        *)
            echo "usage: $0 [--output-dir <dir>]" >&2
            exit 2
            ;;
    esac
done

resolve_upstream_commit() {
    if [[ -n "${UPSTREAM_COMMIT:-}" ]]; then
        printf '%s\n' "$UPSTREAM_COMMIT"
        return 0
    fi

    if [[ -f "$PROJECT_ROOT/upstream.lock" ]]; then
        local lock_commit
        lock_commit="$(grep -E '^upstream_commit=' "$PROJECT_ROOT/upstream.lock" | head -n 1 | cut -d= -f2- || true)"
        if [[ -n "$lock_commit" ]]; then
            printf '%s\n' "$lock_commit"
            return 0
        fi
    fi

    local resolved_shell
    resolved_shell="$(cd "$PROJECT_ROOT" && ./scripts/upstream-sync/upstream.sh resolve --format shell)"
    eval "$resolved_shell"
    if [[ -z "${R47_RESOLVED_UPSTREAM_COMMIT:-}" ]]; then
        echo "ERROR: Failed to resolve the authoritative upstream commit" >&2
        exit 1
    fi
    printf '%s\n' "$R47_RESOLVED_UPSTREAM_COMMIT"
}

UPSTREAM_COMMIT_VALUE="$(resolve_upstream_commit)"
# The manifest names the core the staged tree was built from, or the fixtures lie.
STAGED_CORE="$(sed -n 's/^R47_STAGED_CORE_VERSION=//p' "$STAGED_CPP_DIR/STAGED-INPUTS.properties" 2>/dev/null || true)"
if [ -z "$STAGED_CORE" ] || [[ "$UPSTREAM_COMMIT_VALUE" != "$STAGED_CORE"* ]]; then
    echo "ERROR: upstream $UPSTREAM_COMMIT_VALUE is not the staged core (${STAGED_CORE:-none}): stage it again." >&2
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/objects" "$OUTPUT_DIR"

bash "$PROJECT_ROOT/scripts/android/generate_staged_native_metadata.sh" --cpp-dir "$STAGED_CPP_DIR"

JDK_HOME="$(resolve_jdk_home)"

mapfile -t STAGED_C47_SOURCES < <(find "$STAGED_CPP_DIR/c47" -type f -name '*.c' ! -name 'reservedRegisterLookupGenerator.c' | LC_ALL=C sort)
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

COMPILE_FLAGS=(-std=gnu17 -O0 -g -pthread
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
    -include "$TRACKED_CPP_DIR/r47zen/android_mocks.h")

# One object per source, numbered so that two files of the same name cannot collide,
# compiled in parallel; then one link.
SOURCES=(
    "$PROJECT_ROOT/scripts/keypad-fixtures/keypad_fixture_exporter.c"
    "${STAGED_C47_SOURCES[@]}"
    "${STAGED_GENERATED_SOURCES[@]}"
    "${STAGED_GMP_SOURCES[@]}"
    "${STAGED_DEC_SOURCES[@]}"
    "${ANDROID_BRIDGE_SOURCES[@]}"
)
for index in "${!SOURCES[@]}"; do
    printf '%s\0%s\0' "$BUILD_DIR/objects/$index.o" "${SOURCES[$index]}"
done | xargs -0 -n 2 -P "$(detect_job_count)" cc "${COMPILE_FLAGS[@]}" -c -o
cc -pthread "$BUILD_DIR"/objects/*.o -lm -o "$BUILD_DIR/r47-keypad-fixture-exporter"

"$BUILD_DIR/r47-keypad-fixture-exporter" \
    --output-dir "$OUTPUT_DIR" \
    --upstream-commit "$UPSTREAM_COMMIT_VALUE"
