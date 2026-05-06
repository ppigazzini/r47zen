#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_PROJECT_DIR="$PROJECT_ROOT/android"
MINI_GMP_FALLBACK_DIR="$ANDROID_PROJECT_DIR/compat/mini-gmp-fallback"
CPP_DIR="${R47_ANDROID_STAGED_CPP_DIR:-$ANDROID_PROJECT_DIR/.staged-native/cpp}"
CORE_HASH="${R47_CORE_HASH:-unknown}"
METADATA_SCRIPT="$SCRIPT_DIR/generate_staged_native_metadata.sh"
INPUTS_SCRIPT="$SCRIPT_DIR/compute_staged_native_inputs.sh"

usage() {
        cat <<'EOF'
Usage:
    scripts/android/stage_native_sources.sh [--cpp-dir <dir>]
EOF
}

require_dir() {
    local path="$1"
    local description="$2"

    if [ ! -d "$path" ]; then
        echo "ERROR: Missing $description at $path"
        echo "Run ./scripts/upstream-sync/upstream.sh sync --auto --write-lock and then regenerate Android build.sim assets before staging Android native inputs."
        exit 1
    fi
}

require_file() {
    local path="$1"
    local description="$2"

    if [ ! -f "$path" ]; then
        echo "ERROR: Missing $description at $path"
        echo "Run ./scripts/android/build_android.sh without --android-only or scripts/android/build_sim_assets.sh before staging Android native inputs."
        exit 1
    fi
}

stage_tree() {
    local source_dir="$1"
    local dest_dir="$2"

    rm -rf "$dest_dir"
    mkdir -p "$dest_dir"
    cp -R "$source_dir"/. "$dest_dir"/
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --cpp-dir)
            shift
            [ "$#" -gt 0 ] || {
                echo "ERROR: Missing value for --cpp-dir"
                exit 1
            }
            CPP_DIR="$1"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: Unknown option: $1"
            usage
            exit 1
            ;;
    esac
    shift
done

GENERATED_DEST="$CPP_DIR/generated"

echo "--- Staging Android native inputs into $CPP_DIR ---"

require_dir "$PROJECT_ROOT/src/c47" "synced core tree"
require_dir "$PROJECT_ROOT/dep/decNumberICU" "decNumberICU source tree"

stage_tree "$PROJECT_ROOT/src/c47" "$CPP_DIR/c47"
stage_tree "$PROJECT_ROOT/dep/decNumberICU" "$CPP_DIR/decNumberICU"

echo "--- Staging generated native files ---"
rm -rf "$GENERATED_DEST"
mkdir -p "$GENERATED_DEST"

generated_files=(
    "$PROJECT_ROOT/build.sim/src/generateCatalogs/softmenuCatalogs.h"
    "$PROJECT_ROOT/build.sim/src/generateConstants/constantPointers.h"
    "$PROJECT_ROOT/build.sim/src/generateConstants/constantPointers.c"
    "$PROJECT_ROOT/build.sim/src/generateConstants/constantPointers2.c"
    "$PROJECT_ROOT/build.sim/src/ttf2RasterFonts/rasterFontsData.c"
    "$PROJECT_ROOT/build.sim/src/c47/version.h"
)

for generated_file in "${generated_files[@]}"; do
    require_file "$generated_file" "generated native artifact"
    cp -v "$generated_file" "$GENERATED_DEST/"
done

cat > "$GENERATED_DEST/vcs.h" <<EOF
#if !defined(VCS_H)
  #define VCS_H
  #define VCS_COMMIT_ID  "$CORE_HASH-mod"
#endif
EOF

echo "--- Staging mini-gmp ---"
GMP_SOURCE_DIR="$MINI_GMP_FALLBACK_DIR"

if [ ! -f "$GMP_SOURCE_DIR/mini-gmp.c" ] || { [ ! -f "$GMP_SOURCE_DIR/mini-gmp.h" ] && [ ! -f "$GMP_SOURCE_DIR/gmp.h" ]; }; then
    echo "ERROR: Missing Android mini-gmp fallback sources at $MINI_GMP_FALLBACK_DIR."
    exit 1
fi

rm -rf "$CPP_DIR/gmp"
mkdir -p "$CPP_DIR/gmp"

echo "Using Android mini-gmp fallback sources from $GMP_SOURCE_DIR"
cp -v "$GMP_SOURCE_DIR/mini-gmp.c" "$CPP_DIR/gmp/"

if [ -f "$GMP_SOURCE_DIR/mini-gmp.h" ]; then
    cp -v "$GMP_SOURCE_DIR/mini-gmp.h" "$CPP_DIR/gmp/mini-gmp.h"
    cp -v "$GMP_SOURCE_DIR/mini-gmp.h" "$CPP_DIR/gmp/gmp.h"
else
    cp -v "$GMP_SOURCE_DIR/gmp.h" "$CPP_DIR/gmp/gmp.h"
    cp -v "$GMP_SOURCE_DIR/gmp.h" "$CPP_DIR/gmp/mini-gmp.h"
fi

echo "--- Writing staged native source metadata ---"
bash "$METADATA_SCRIPT" --cpp-dir "$CPP_DIR"

echo "--- Recording staged native input fingerprint ---"
R47_STAGED_CORE_VERSION="$CORE_HASH" bash "$INPUTS_SCRIPT" --output "$CPP_DIR/STAGED-INPUTS.properties"

echo "--- Android native staging complete ---"