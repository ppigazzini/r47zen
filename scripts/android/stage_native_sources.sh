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

# Copy a single file only when its content differs from the destination, so an
# unchanged input keeps its old mtime and a preserved app/.cxx can skip the
# matching native recompile. Changed or new files get a current mtime, which
# forces ninja to rebuild exactly those translation units.
copy_file_if_changed() {
    local src="$1"
    local dst="$2"

    if [ ! -f "$dst" ] || ! cmp -s "$src" "$dst"; then
        mkdir -p "$(dirname "$dst")"
        cp "$src" "$dst"
    fi
}

# Mirror a source tree into the staged tree without rewriting unchanged files,
# preserving their mtimes for incremental native builds. Files removed upstream
# are pruned so the staged set still matches the source exactly, matching the
# previous wipe-and-copy semantics for the staged input fingerprint.
stage_tree() {
    local source_dir="$1"
    local dest_dir="$2"
    local src dst rel

    mkdir -p "$dest_dir"

    while IFS= read -r -d '' src; do
        rel="${src#"$source_dir"/}"
        copy_file_if_changed "$src" "$dest_dir/$rel"
    done < <(find "$source_dir" -type f -print0)

    while IFS= read -r -d '' dst; do
        rel="${dst#"$dest_dir"/}"
        [ -f "$source_dir/$rel" ] || rm -f "$dst"
    done < <(find "$dest_dir" -type f -print0)
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
        -h | --help)
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
    copy_file_if_changed "$generated_file" "$GENERATED_DEST/$(basename "$generated_file")"
done

generated_vcs="$(mktemp)"
cat >"$generated_vcs" <<EOF
#if !defined(VCS_H)
  #define VCS_H
  #define VCS_COMMIT_ID  "$CORE_HASH-mod"
#endif
EOF
copy_file_if_changed "$generated_vcs" "$GENERATED_DEST/vcs.h"
rm -f "$generated_vcs"

echo "--- Staging mini-gmp ---"
GMP_SOURCE_DIR="$MINI_GMP_FALLBACK_DIR"

if [ ! -f "$GMP_SOURCE_DIR/mini-gmp.c" ] || { [ ! -f "$GMP_SOURCE_DIR/mini-gmp.h" ] && [ ! -f "$GMP_SOURCE_DIR/gmp.h" ]; }; then
    echo "ERROR: Missing Android mini-gmp fallback sources at $MINI_GMP_FALLBACK_DIR."
    exit 1
fi

mkdir -p "$CPP_DIR/gmp"

echo "Using Android mini-gmp fallback sources from $GMP_SOURCE_DIR"
copy_file_if_changed "$GMP_SOURCE_DIR/mini-gmp.c" "$CPP_DIR/gmp/mini-gmp.c"

if [ -f "$GMP_SOURCE_DIR/mini-gmp.h" ]; then
    copy_file_if_changed "$GMP_SOURCE_DIR/mini-gmp.h" "$CPP_DIR/gmp/mini-gmp.h"
    copy_file_if_changed "$GMP_SOURCE_DIR/mini-gmp.h" "$CPP_DIR/gmp/gmp.h"
else
    copy_file_if_changed "$GMP_SOURCE_DIR/gmp.h" "$CPP_DIR/gmp/gmp.h"
    copy_file_if_changed "$GMP_SOURCE_DIR/gmp.h" "$CPP_DIR/gmp/mini-gmp.h"
fi

echo "--- Writing staged native source metadata ---"
bash "$METADATA_SCRIPT" --cpp-dir "$CPP_DIR"

echo "--- Recording staged native input fingerprint ---"
R47_STAGED_CORE_VERSION="$CORE_HASH" bash "$INPUTS_SCRIPT" --output "$CPP_DIR/STAGED-INPUTS.properties"

echo "--- Android native staging complete ---"
