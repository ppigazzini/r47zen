#!/bin/bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MINI_GMP_FALLBACK_DIR="$PROJECT_ROOT/android/compat/mini-gmp-fallback"
OUTPUT_PATH=""
STAGED_CORE_VERSION="${R47_STAGED_CORE_VERSION:-unknown}"

font_asset_files=(
    "C47__NumericFont.ttf"
    "C47__StandardFont.ttf"
    "C47__TinyFont.ttf"
    "sortingOrder.xlsx"
)

generated_files=(
    "$PROJECT_ROOT/build.sim/src/generateCatalogs/softmenuCatalogs.h"
    "$PROJECT_ROOT/build.sim/src/generateConstants/constantPointers.h"
    "$PROJECT_ROOT/build.sim/src/generateConstants/constantPointers.c"
    "$PROJECT_ROOT/build.sim/src/generateConstants/constantPointers2.c"
    "$PROJECT_ROOT/build.sim/src/ttf2RasterFonts/rasterFontsData.c"
    "$PROJECT_ROOT/build.sim/src/c47/version.h"
)

usage() {
    cat <<'EOF'
Usage:
    scripts/android/compute_staged_native_inputs.sh [--output <file>]
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

require_dir() {
    local path="$1"
    local description="$2"

    [ -d "$path" ] || fail "Missing ${description} at ${path}"
}

require_file() {
    local path="$1"
    local description="$2"

    [ -f "$path" ] || fail "Missing ${description} at ${path}"
}

replace_if_different() {
    local temp_path="$1"
    local final_path="$2"

    if [ -f "$final_path" ] && cmp -s "$temp_path" "$final_path"; then
        rm -f "$temp_path"
        return 0
    fi

    mv "$temp_path" "$final_path"
}

resolve_sha256_command() {
    if command -v sha256sum >/dev/null 2>&1; then
        SHA256_CMD=(sha256sum)
        return 0
    fi

    if command -v shasum >/dev/null 2>&1; then
        SHA256_CMD=(shasum -a 256)
        return 0
    fi

    fail "Could not find sha256sum or shasum to compute staged-native fingerprints."
}

relative_to_project_root() {
    local path="$1"

    case "$path" in
        "$PROJECT_ROOT"/*)
            printf '%s\n' "${path#$PROJECT_ROOT/}"
            ;;
        *)
            printf '%s\n' "$path"
            ;;
    esac
}

find_gmp_source_dir() {
    if [ -f "$MINI_GMP_FALLBACK_DIR/mini-gmp.c" ] && { [ -f "$MINI_GMP_FALLBACK_DIR/mini-gmp.h" ] || [ -f "$MINI_GMP_FALLBACK_DIR/gmp.h" ]; }; then
        printf '%s\n' "$MINI_GMP_FALLBACK_DIR"
        return 0
    fi

    fail "Could not locate Android mini-gmp fallback sources at ${MINI_GMP_FALLBACK_DIR}."
}

font_source_dir_has_required_fonts() {
    local candidate_dir="$1"
    local font_file=""

    [ -d "$candidate_dir" ] || return 1

    for font_file in "${font_asset_files[@]}"; do
        [ -f "$candidate_dir/$font_file" ] || return 1
    done

    return 0
}

resolve_font_source_dir() {
    local candidate="$PROJECT_ROOT/res/fonts"

    if font_source_dir_has_required_fonts "$candidate"; then
        printf '%s\n' "$candidate"
        return 0
    fi

    fail "Could not locate canonical calculator font assets in ${candidate}."
}

digest_tree() {
    local source_dir="$1"
    local temp_manifest=""
    local digest=""
    local absolute_path=""
    local relative_path=""

    require_dir "$source_dir" "fingerprint source tree"

    temp_manifest=$(mktemp)

    while IFS= read -r absolute_path; do
        relative_path="${absolute_path#$source_dir/}"
        digest=$("${SHA256_CMD[@]}" "$absolute_path" | awk '{print $1}')
        printf '%s  %s\n' "$digest" "$relative_path" >> "$temp_manifest"
    done < <(find "$source_dir" -type f | LC_ALL=C sort)

    digest=$("${SHA256_CMD[@]}" "$temp_manifest" | awk '{print $1}')
    rm -f "$temp_manifest"

    printf '%s\n' "$digest"
}

digest_explicit_files() {
    local temp_manifest=""
    local digest=""
    local file_path=""

    temp_manifest=$(mktemp)

    for file_path in "$@"; do
        require_file "$file_path" "fingerprint source file"
        digest=$("${SHA256_CMD[@]}" "$file_path" | awk '{print $1}')
        printf '%s  %s\n' "$digest" "$(relative_to_project_root "$file_path")" >> "$temp_manifest"
    done

    digest=$("${SHA256_CMD[@]}" "$temp_manifest" | awk '{print $1}')
    rm -f "$temp_manifest"

    printf '%s\n' "$digest"
}

emit_output() {
    local output_path="$1"
    local font_source_dir="$2"
    local gmp_source_dir="$3"
    local c47_fingerprint="$4"
    local decnumber_fingerprint="$5"
    local generated_fingerprint="$6"
    local fonts_fingerprint="$7"
    local gmp_fingerprint="$8"
    local combined_fingerprint="$9"
    local temp_output=""

    temp_output=$(mktemp)

    cat > "$temp_output" <<EOF
R47_STAGED_CORE_VERSION=$STAGED_CORE_VERSION
R47_STAGED_FONT_SOURCE=$(relative_to_project_root "$font_source_dir")
R47_STAGED_GMP_SOURCE=$(relative_to_project_root "$gmp_source_dir")
R47_STAGED_INPUTS_C47_FINGERPRINT=$c47_fingerprint
R47_STAGED_INPUTS_DECNUMBERICU_FINGERPRINT=$decnumber_fingerprint
R47_STAGED_INPUTS_GENERATED_FINGERPRINT=$generated_fingerprint
R47_STAGED_INPUTS_FONTS_FINGERPRINT=$fonts_fingerprint
R47_STAGED_INPUTS_GMP_FINGERPRINT=$gmp_fingerprint
R47_STAGED_INPUTS_COMBINED_FINGERPRINT=$combined_fingerprint
EOF

    if [ -n "$output_path" ]; then
        replace_if_different "$temp_output" "$output_path"
    else
        cat "$temp_output"
        rm -f "$temp_output"
    fi
}

main() {
    local font_source_dir=""
    local gmp_source_dir=""
    local c47_fingerprint=""
    local decnumber_fingerprint=""
    local generated_fingerprint=""
    local fonts_fingerprint=""
    local gmp_fingerprint=""
    local combined_fingerprint=""
    local temp_manifest=""

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --output)
                shift
                [ "$#" -gt 0 ] || fail "Missing value for --output"
                OUTPUT_PATH="$1"
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fail "Unknown option: $1"
                ;;
        esac
        shift
    done

    resolve_sha256_command
    require_dir "$PROJECT_ROOT/src/c47" "synced core tree"
    require_dir "$PROJECT_ROOT/dep/decNumberICU" "decNumberICU source tree"
    font_source_dir=$(resolve_font_source_dir)
    gmp_source_dir=$(find_gmp_source_dir)

    c47_fingerprint=$(digest_tree "$PROJECT_ROOT/src/c47")
    decnumber_fingerprint=$(digest_tree "$PROJECT_ROOT/dep/decNumberICU")
    generated_fingerprint=$(digest_explicit_files "${generated_files[@]}")
    fonts_fingerprint=$(digest_explicit_files \
        "$font_source_dir/C47__NumericFont.ttf" \
        "$font_source_dir/C47__StandardFont.ttf" \
        "$font_source_dir/C47__TinyFont.ttf")
    gmp_fingerprint=$(digest_tree "$gmp_source_dir")

    temp_manifest=$(mktemp)
    printf 'c47=%s\n' "$c47_fingerprint" >> "$temp_manifest"
    printf 'decNumberICU=%s\n' "$decnumber_fingerprint" >> "$temp_manifest"
    printf 'generated=%s\n' "$generated_fingerprint" >> "$temp_manifest"
    printf 'fonts=%s\n' "$fonts_fingerprint" >> "$temp_manifest"
    printf 'gmp=%s\n' "$gmp_fingerprint" >> "$temp_manifest"
    combined_fingerprint=$("${SHA256_CMD[@]}" "$temp_manifest" | awk '{print $1}')
    rm -f "$temp_manifest"

    emit_output \
        "$OUTPUT_PATH" \
        "$font_source_dir" \
        "$gmp_source_dir" \
        "$c47_fingerprint" \
        "$decnumber_fingerprint" \
        "$generated_fingerprint" \
        "$fonts_fingerprint" \
        "$gmp_fingerprint" \
        "$combined_fingerprint"
}

main "$@"