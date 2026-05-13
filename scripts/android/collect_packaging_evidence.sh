#!/bin/bash

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/android/collect_packaging_evidence.sh \
    --variant <debug|release> \
    (--apk <path> | --bundle <path>) \
    --output-dir <path> \
    [--artifact-name <file-name>] \
    [--expected-abis <abi[,abi...]>] \
    [--android-sdk-root <path>] \
    [--ndk-version <version>] \
    [--compile-sdk <sdk>] \
    [--cmake-version <version>] \
    [--android-source-repository-url <url>] \
    [--android-source-commit <commit>] \
    [--upstream-source-repository-url <url>] \
    [--upstream-source-commit <commit>] \
    [--xlsxio-source-repository-url <url>] \
    [--xlsxio-source-commit <commit>] \
    [--application-id <id>] \
    [--version-code <code>] \
    [--version-name <name>] \
    [--signing-mode <debug|release|unsigned>] \
    [--ref <git-ref>] \
    [--sha <git-sha>] \
    [--run-id <id>] \
    [--run-attempt <attempt>] \
    [--mapping-file <path>] \
    [--native-symbols <path>]
EOF
}

variant=""
apk_path=""
bundle_path=""
output_dir=""
artifact_name=""
expected_abis=""
android_sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ndk_version=""
compile_sdk=""
cmake_version=""
android_source_repository_url=""
android_source_commit=""
upstream_source_repository_url=""
upstream_source_commit=""
xlsxio_source_repository_url=""
xlsxio_source_commit=""
application_id=""
version_code=""
version_name=""
signing_mode="unknown"
git_ref=""
git_sha=""
run_id=""
run_attempt=""
mapping_file=""
native_symbols_file=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --variant)
            variant="$2"
            shift 2
            ;;
        --apk)
            apk_path="$2"
            shift 2
            ;;
        --bundle)
            bundle_path="$2"
            shift 2
            ;;
        --output-dir)
            output_dir="$2"
            shift 2
            ;;
        --artifact-name)
            artifact_name="$2"
            shift 2
            ;;
        --expected-abis)
            expected_abis="$2"
            shift 2
            ;;
        --android-sdk-root)
            android_sdk_root="$2"
            shift 2
            ;;
        --ndk-version)
            ndk_version="$2"
            shift 2
            ;;
        --compile-sdk)
            compile_sdk="$2"
            shift 2
            ;;
        --cmake-version)
            cmake_version="$2"
            shift 2
            ;;
        --android-source-repository-url)
            android_source_repository_url="$2"
            shift 2
            ;;
        --android-source-commit)
            android_source_commit="$2"
            shift 2
            ;;
        --upstream-source-repository-url)
            upstream_source_repository_url="$2"
            shift 2
            ;;
        --upstream-source-commit)
            upstream_source_commit="$2"
            shift 2
            ;;
        --xlsxio-source-repository-url)
            xlsxio_source_repository_url="$2"
            shift 2
            ;;
        --xlsxio-source-commit)
            xlsxio_source_commit="$2"
            shift 2
            ;;
        --application-id)
            application_id="$2"
            shift 2
            ;;
        --version-code)
            version_code="$2"
            shift 2
            ;;
        --version-name)
            version_name="$2"
            shift 2
            ;;
        --signing-mode)
            signing_mode="$2"
            shift 2
            ;;
        --ref)
            git_ref="$2"
            shift 2
            ;;
        --sha)
            git_sha="$2"
            shift 2
            ;;
        --run-id)
            run_id="$2"
            shift 2
            ;;
        --run-attempt)
            run_attempt="$2"
            shift 2
            ;;
        --mapping-file)
            mapping_file="$2"
            shift 2
            ;;
        --native-symbols)
            native_symbols_file="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "$variant" || -z "$output_dir" ]]; then
    echo "Missing required arguments." >&2
    usage >&2
    exit 1
fi

if [[ -n "$apk_path" && -n "$bundle_path" ]]; then
    echo "Provide only one primary artifact: --apk or --bundle." >&2
    exit 1
fi

if [[ -z "$apk_path" && -z "$bundle_path" ]]; then
    echo "Provide one primary artifact with --apk or --bundle." >&2
    exit 1
fi

if [[ -n "$apk_path" && ! -f "$apk_path" ]]; then
    echo "APK not found: $apk_path" >&2
    exit 1
fi

if [[ -n "$bundle_path" && ! -f "$bundle_path" ]]; then
    echo "App bundle not found: $bundle_path" >&2
    exit 1
fi

if [[ -n "$mapping_file" && ! -f "$mapping_file" ]]; then
    echo "Mapping file not found: $mapping_file" >&2
    exit 1
fi

if [[ -n "$native_symbols_file" && ! -f "$native_symbols_file" ]]; then
    echo "Native symbols archive not found: $native_symbols_file" >&2
    exit 1
fi

mkdir -p "$output_dir"

primary_artifact_path="$apk_path"
artifact_type="apk"
if [[ -n "$bundle_path" ]]; then
    primary_artifact_path="$bundle_path"
    artifact_type="aab"
fi

if [[ -z "$artifact_name" ]]; then
    artifact_name="$(basename "$primary_artifact_path")"
fi

release_artifact="$output_dir/$artifact_name"
cp "$primary_artifact_path" "$release_artifact"

tmp_root="${TMPDIR:-$(dirname "$output_dir")}"
mkdir -p "$tmp_root"
tmp_dir="$(mktemp -d "$tmp_root/r47-packaging.XXXXXX")"
cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT

extract_packaged_compliance_assets() {
    local unpack_dir="$1"
    local asset_root="$2"
    local destination_dir="$3"
    local compliance_dir="$destination_dir/compliance-assets"
    local copied_any=0

    mkdir -p "$compliance_dir"

    for asset_name in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json; do
        if [[ -f "$unpack_dir/$asset_root/$asset_name" ]]; then
            cp "$unpack_dir/$asset_root/$asset_name" "$compliance_dir/$asset_name"
            copied_any=1
        fi
    done

    if [[ -d "$unpack_dir/$asset_root/repo-notices" ]]; then
        rm -rf "$compliance_dir/repo-notices"
        cp -R "$unpack_dir/$asset_root/repo-notices" "$compliance_dir/repo-notices"
        copied_any=1
    fi

    if [[ "$copied_any" -eq 1 ]]; then
        find "$compliance_dir" -type f | sed "s#^$destination_dir/##" | sort > "$destination_dir/PACKAGED-COMPLIANCE-ASSETS.txt"
        return 0
    else
        rm -rf "$compliance_dir"
        return 1
    fi
}

write_metadata_line() {
    local key="$1"
    local value="$2"
    local target_file="$3"
    if [[ -n "$value" ]]; then
        printf '%s=%s\n' "$key" "$value" >> "$target_file"
    fi
}

write_xlsxio_license() {
    local target_file="$1"
    cat > "$target_file" <<'EOF'
Copyright (C) 2016 Brecht Sanders All Rights Reserved

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
EOF
}

write_source_manifest() {
    local target_file="$1"
    : > "$target_file"
    write_metadata_line "upstream_url" "$upstream_source_repository_url" "$target_file"
    write_metadata_line "upstream_commit" "$upstream_source_commit" "$target_file"
    write_metadata_line "android_source_repository_url" "$android_source_repository_url" "$target_file"
    write_metadata_line "android_source_commit" "$android_source_commit" "$target_file"
    write_metadata_line "xlsxio_url" "$xlsxio_source_repository_url" "$target_file"
    write_metadata_line "xlsxio_commit" "$xlsxio_source_commit" "$target_file"
}

populate_compliance_assets() {
    local destination_dir="$1"
    local compliance_dir="$destination_dir/compliance-assets"

    mkdir -p "$compliance_dir"

    if [[ ! -f "$compliance_dir/SOURCE" ]]; then
        write_source_manifest "$compliance_dir/SOURCE"
    fi

    if [[ ! -f "$compliance_dir/COPYING" ]]; then
        cp "$(dirname "$0")/../COPYING" "$compliance_dir/COPYING"
    fi

    if [[ ! -f "$compliance_dir/LICENSE.txt" ]]; then
        write_xlsxio_license "$compliance_dir/LICENSE.txt"
    fi
}

validate_compliance_assets_layout() {
    local destination_dir="$1"
    local compliance_dir="$destination_dir/compliance-assets"
    local duplicate_paths=()

    if [[ ! -d "$compliance_dir" ]]; then
        echo "Missing compliance-assets directory in packaging evidence output." >&2
        exit 1
    fi

    for duplicate_path in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json repo-notices; do
        if [[ -e "$destination_dir/$duplicate_path" ]]; then
            duplicate_paths+=("$duplicate_path")
        fi
    done

    if [[ ${#duplicate_paths[@]} -gt 0 ]]; then
        echo "Compliance evidence must live under compliance-assets only; found duplicate top-level entries:" >&2
        printf '  %s\n' "${duplicate_paths[@]}" >&2
        exit 1
    fi

    if [[ -f "$destination_dir/PACKAGED-COMPLIANCE-ASSETS.txt" ]]; then
        if grep -Ev '^compliance-assets/' "$destination_dir/PACKAGED-COMPLIANCE-ASSETS.txt" >/dev/null 2>&1; then
            echo "PACKAGED-COMPLIANCE-ASSETS.txt must reference compliance-assets/ paths only." >&2
            exit 1
        fi
    fi
}

require_packaged_compliance_assets() {
    local destination_dir="$1"
    local artifact_label="$2"

    if [[ ! -s "$destination_dir/PACKAGED-COMPLIANCE-ASSETS.txt" ]]; then
        echo "Did not find packaged compliance assets while inspecting the ${artifact_label}." >&2
        exit 1
    fi
}

if [[ "$artifact_type" == "apk" || "$artifact_type" == "aab" ]]; then
    unpack_dir="$tmp_dir/${artifact_type}-unpacked"
    mkdir -p "$unpack_dir"
    unzip -q "$primary_artifact_path" -d "$unpack_dir"
fi

if [[ "$artifact_type" == "apk" ]]; then
    if [[ -z "$android_sdk_root" || -z "$ndk_version" ]]; then
        echo "APK evidence collection requires --android-sdk-root and --ndk-version." >&2
        exit 1
    fi

    extract_packaged_compliance_assets "$unpack_dir" "assets" "$output_dir"

    if [[ -n "$expected_abis" ]]; then
        expected_file="$tmp_dir/expected-abis.txt"
        actual_file="$output_dir/abis.txt"
        tr ',' '\n' <<< "$expected_abis" | sed '/^$/d' | sort > "$expected_file"
        find "$unpack_dir/lib" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | tee "$actual_file"
        diff -u "$expected_file" "$actual_file"
    fi

    zipalign_bin="$(find "$android_sdk_root/build-tools" -type f -name zipalign | sort -V | tail -n 1)"
    llvm_objdump="$(find "$android_sdk_root/ndk/$ndk_version/toolchains/llvm/prebuilt" -type f -name llvm-objdump | sort | head -n 1)"

    if [[ -z "$zipalign_bin" || ! -x "$zipalign_bin" ]]; then
        echo "Unable to locate zipalign under $android_sdk_root/build-tools." >&2
        exit 1
    fi

    if [[ -z "$llvm_objdump" || ! -x "$llvm_objdump" ]]; then
        echo "Unable to locate llvm-objdump for NDK $ndk_version." >&2
        exit 1
    fi

    "$zipalign_bin" -c -P 16 -v 4 "$primary_artifact_path" | tee "$output_dir/zipalign.txt"

    elf_report="$output_dir/elf-load-segments.txt"
    below_16k_report="$tmp_dir/elf-load-segments-below-16k.txt"
    : > "$elf_report"
    while IFS= read -r -d '' so_file; do
        {
            echo "== ${so_file#$unpack_dir/} =="
            "$llvm_objdump" -p "$so_file" | grep 'LOAD'
            echo
        } >> "$elf_report"
    done < <(find "$unpack_dir/lib" -type f -name '*.so' -print0)

    awk '
        /^== / {
            current = $0
            next
        }

        /align 2\*\*[0-9]+/ {
            if (match($0, /align 2\*\*[0-9]+/)) {
                exponent_text = $0
                sub(/.*align 2\*\*/, "", exponent_text)
                sub(/[^0-9].*$/, "", exponent_text)
                exponent = exponent_text + 0
                if (exponent < 14) {
                    if (current != "" && current != last_header) {
                        print current
                        last_header = current
                    }
                    print $0
                }
            }
        }
    ' "$elf_report" > "$below_16k_report"

    if [[ -s "$below_16k_report" ]]; then
        cat "$below_16k_report" >&2
        echo "Detected a native library LOAD segment aligned below 16 KB." >&2
        exit 1
    fi
fi

if [[ "$artifact_type" == "aab" ]]; then
    extract_packaged_compliance_assets "$unpack_dir" "base/assets" "$output_dir"
fi

populate_compliance_assets "$output_dir"
validate_compliance_assets_layout "$output_dir"

if [[ "$artifact_type" == "apk" || "$artifact_type" == "aab" ]]; then
    require_packaged_compliance_assets "$output_dir" "$artifact_type"
fi

if [[ -n "$mapping_file" ]]; then
    cp "$mapping_file" "$output_dir/$(basename "$mapping_file")"
fi

if [[ -n "$native_symbols_file" ]]; then
    cp "$native_symbols_file" "$output_dir/$(basename "$native_symbols_file")"
fi

if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$release_artifact" > "$output_dir/SHA256SUMS.txt"
elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$release_artifact" > "$output_dir/SHA256SUMS.txt"
else
    echo "Neither sha256sum nor shasum is available." >&2
    exit 1
fi

build_metadata_file="$output_dir/BUILD-METADATA.txt"
: > "$build_metadata_file"
write_metadata_line "ref" "$git_ref" "$build_metadata_file"
write_metadata_line "sha" "$git_sha" "$build_metadata_file"
write_metadata_line "run_id" "$run_id" "$build_metadata_file"
write_metadata_line "run_attempt" "$run_attempt" "$build_metadata_file"
write_metadata_line "upstream_url" "$upstream_source_repository_url" "$build_metadata_file"
write_metadata_line "upstream_commit" "$upstream_source_commit" "$build_metadata_file"
write_metadata_line "upstream_license_file" "compliance-assets/COPYING" "$build_metadata_file"
write_metadata_line "android_source_repository_url" "$android_source_repository_url" "$build_metadata_file"
write_metadata_line "android_source_commit" "$android_source_commit" "$build_metadata_file"
write_metadata_line "source_manifest_file" "compliance-assets/SOURCE" "$build_metadata_file"
write_metadata_line "xlsxio_url" "$xlsxio_source_repository_url" "$build_metadata_file"
write_metadata_line "xlsxio_commit" "$xlsxio_source_commit" "$build_metadata_file"
write_metadata_line "xlsxio_license_file" "compliance-assets/LICENSE.txt" "$build_metadata_file"
if [[ -f "$output_dir/compliance-assets/THIRD-PARTY.spdx.json" ]]; then
    write_metadata_line "spdx_inventory_file" "compliance-assets/THIRD-PARTY.spdx.json" "$build_metadata_file"
fi
write_metadata_line "application_id" "$application_id" "$build_metadata_file"
write_metadata_line "version_code" "$version_code" "$build_metadata_file"
write_metadata_line "version_name" "$version_name" "$build_metadata_file"
write_metadata_line "compile_sdk" "$compile_sdk" "$build_metadata_file"
write_metadata_line "cmake_version" "$cmake_version" "$build_metadata_file"
write_metadata_line "ndk_version" "$ndk_version" "$build_metadata_file"
write_metadata_line "android_variant" "$variant" "$build_metadata_file"
write_metadata_line "artifact_type" "$artifact_type" "$build_metadata_file"
write_metadata_line "artifact_name" "$artifact_name" "$build_metadata_file"
write_metadata_line "artifact_signed" "$signing_mode" "$build_metadata_file"

echo "Packaging evidence written to $output_dir"
