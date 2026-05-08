#!/bin/bash

set -Eeuo pipefail

usage() {
    cat <<'EOF'
Usage:
    scripts/package-notices/generate_simulator_notice_artifacts.sh \
    --platform <linux|windows> \
    --package-dir <path> \
    --upstream-source-repository-url <url> \
    --upstream-source-commit <commit> \
    [--xlsxio-source-repository-url <url>] \
    [--xlsxio-source-commit <commit>]
EOF
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/../.." && pwd)"

platform=""
package_dir=""
upstream_source_repository_url=""
upstream_source_commit=""
xlsxio_source_repository_url=""
xlsxio_source_commit=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --platform)
            platform="$2"
            shift 2
            ;;
        --package-dir)
            package_dir="$2"
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

if [[ "$platform" != "linux" && "$platform" != "windows" ]]; then
    echo "Missing or unsupported --platform argument: $platform" >&2
    usage >&2
    exit 1
fi

if [[ -z "$package_dir" ]]; then
    echo "Missing required --package-dir argument." >&2
    usage >&2
    exit 1
fi

if [[ ! -d "$package_dir" ]]; then
    echo "Package directory not found: $package_dir" >&2
    exit 1
fi

if [[ -z "$upstream_source_repository_url" || -z "$upstream_source_commit" ]]; then
    echo "Missing required upstream source metadata." >&2
    usage >&2
    exit 1
fi

copying_source="$repo_root/COPYING"
decnumbericu_source="$repo_root/dep/decNumberICU/ICU-license.html"
xlsxio_license_source="$repo_root/android/compliance/repo-notices/source-texts/xlsxio-mit.txt"

for required_path in "$copying_source" "$decnumbericu_source" "$xlsxio_license_source"; do
    if [[ ! -f "$required_path" ]]; then
        echo "Required notice source file not found: $required_path" >&2
        exit 1
    fi
done

notice_root="$package_dir/repo-notices/$platform"
licenses_dir="$notice_root/licenses"
spdx_file="$package_dir/THIRD-PARTY.spdx.json"
source_manifest_file="$package_dir/SOURCE"
artifact_package_name="$(basename "$package_dir")"

runtime_inventory_file=""
runtime_summary_file=""
runtime_license_dir=""

tmp_root="${TMPDIR:-$(dirname "$package_dir")}"
mkdir -p "$tmp_root"
tmp_dir="$(mktemp -d "$tmp_root/r47-${platform}-notices.XXXXXX")"
cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT

json_escape() {
    local value="${1-}"
    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/}
    value=${value//$'\t'/\\t}
    printf '%s' "$value"
}

spdx_id() {
    local value="$1"
    value="$(printf '%s' "$value" | tr -cs 'A-Za-z0-9.-' '-' | sed 's/^-//; s/-$//')"
    if [[ -z "$value" ]]; then
        value="unknown"
    fi
    printf 'SPDXRef-%s' "$value"
}

append_described() {
    local id="$1"

    if [[ -s "$tmp_dir/describes.json" ]]; then
        printf ',\n' >> "$tmp_dir/describes.json"
    fi
    printf '    "%s"' "$(json_escape "$id")" >> "$tmp_dir/describes.json"
}

append_package() {
    local id="$1"
    local name="$2"
    local download_location="$3"
    local license_declared="$4"
    local version_info="$5"
    local summary="$6"
    local license_comments="$7"
    local source_info="$8"
    local homepage="$9"
    local external_ref="${10}"

    if [[ -s "$tmp_dir/packages.json" ]]; then
        printf ',\n' >> "$tmp_dir/packages.json"
    fi

    printf '    {\n' >> "$tmp_dir/packages.json"
    printf '      "SPDXID": "%s",\n' "$(json_escape "$id")" >> "$tmp_dir/packages.json"
    printf '      "name": "%s",\n' "$(json_escape "$name")" >> "$tmp_dir/packages.json"
    printf '      "downloadLocation": "%s",\n' "$(json_escape "${download_location:-NOASSERTION}")" >> "$tmp_dir/packages.json"
    printf '      "filesAnalyzed": false,\n' >> "$tmp_dir/packages.json"
    printf '      "licenseConcluded": "NOASSERTION",\n' >> "$tmp_dir/packages.json"
    printf '      "licenseDeclared": "%s",\n' "$(json_escape "${license_declared:-NOASSERTION}")" >> "$tmp_dir/packages.json"
    printf '      "copyrightText": "NOASSERTION"' >> "$tmp_dir/packages.json"

    if [[ -n "$version_info" ]]; then
        printf ',\n      "versionInfo": "%s"' "$(json_escape "$version_info")" >> "$tmp_dir/packages.json"
    fi
    if [[ -n "$summary" ]]; then
        printf ',\n      "summary": "%s"' "$(json_escape "$summary")" >> "$tmp_dir/packages.json"
    fi
    if [[ -n "$homepage" ]]; then
        printf ',\n      "homepage": "%s"' "$(json_escape "$homepage")" >> "$tmp_dir/packages.json"
    fi
    if [[ -n "$license_comments" ]]; then
        printf ',\n      "licenseComments": "%s"' "$(json_escape "$license_comments")" >> "$tmp_dir/packages.json"
    fi
    if [[ -n "$source_info" ]]; then
        printf ',\n      "sourceInfo": "%s"' "$(json_escape "$source_info")" >> "$tmp_dir/packages.json"
    fi
    if [[ -n "$external_ref" ]]; then
        printf ',\n      "externalRefs": [\n' >> "$tmp_dir/packages.json"
        printf '        {\n' >> "$tmp_dir/packages.json"
        printf '          "referenceCategory": "PACKAGE-MANAGER",\n' >> "$tmp_dir/packages.json"
        printf '          "referenceType": "purl",\n' >> "$tmp_dir/packages.json"
        printf '          "referenceLocator": "%s"\n' "$(json_escape "$external_ref")" >> "$tmp_dir/packages.json"
        printf '        }\n' >> "$tmp_dir/packages.json"
        printf '      ]' >> "$tmp_dir/packages.json"
    fi

    printf '\n    }' >> "$tmp_dir/packages.json"
    append_described "$id"
}

write_common_notice_files() {
    mkdir -p "$notice_root/gpl" "$notice_root/decnumbericu" "$notice_root/xlsxio" "$licenses_dir"

    cp "$copying_source" "$package_dir/COPYING"
    cp "$copying_source" "$notice_root/gpl/COPYING.txt"
    cp "$decnumbericu_source" "$notice_root/decnumbericu/ICU-license.html"
    cp "$xlsxio_license_source" "$package_dir/LICENSE.txt"
    cp "$xlsxio_license_source" "$notice_root/xlsxio/LICENSE.txt"

    cat > "$source_manifest_file" <<EOF
upstream_url=$upstream_source_repository_url
upstream_commit=$upstream_source_commit
upstream_license_file=COPYING
xlsxio_url=${xlsxio_source_repository_url:-}
xlsxio_commit=${xlsxio_source_commit:-}
xlsxio_license_file=LICENSE.txt
notice_root=repo-notices/$platform
EOF
}

append_common_packages() {
    append_package \
        "$(spdx_id 'r47-upstream-core')" \
        "r47-upstream-core" \
        "$upstream_source_repository_url" \
        "GPL-3.0-only" \
        "$upstream_source_commit" \
        "Authoritative upstream core revision synchronized into the packaged simulator artifact." \
        "Overall package license text is shipped as COPYING and repo-notices/$platform/gpl/COPYING.txt." \
        "Recorded from the workflow upstream resolution step." \
        "" \
        ""

    append_package \
        "$(spdx_id 'decnumbericu')" \
        "decNumberICU" \
        "$upstream_source_repository_url" \
        "ICU" \
        "$upstream_source_commit" \
        "Vendored decimal arithmetic support shipped from the synchronized upstream source tree." \
        "Notice copied to repo-notices/$platform/decnumbericu/ICU-license.html." \
        "Notice source is dep/decNumberICU/ICU-license.html in the synchronized upstream checkout." \
        "" \
        ""

    append_package \
        "$(spdx_id 'xlsxio')" \
        "xlsxio" \
        "${xlsxio_source_repository_url:-NOASSERTION}" \
        "MIT" \
        "${xlsxio_source_commit:-unknown}" \
        "Spreadsheet I/O dependency recorded in the simulator package provenance and notices." \
        "Notice copied to LICENSE.txt and repo-notices/$platform/xlsxio/LICENSE.txt." \
        "Version and source URL come from the shared Android defaults or explicit workflow inputs." \
        "" \
        ""
}

linux_package_field() {
    local package_name="$1"
    local field_name="$2"

    dpkg-query -s "$package_name" 2>/dev/null | awk -F ': ' -v wanted="$field_name" '
        $1 == wanted {
            print $2
            exit
        }
    '
}

collect_linux_runtime_notices() {
    if ! command -v dpkg-query >/dev/null 2>&1; then
        echo "This script requires dpkg-query for Linux simulator notice generation." >&2
        exit 1
    fi

    runtime_inventory_file="$notice_root/LINUX-RUNTIME-LIBRARIES.txt"
    runtime_summary_file="$notice_root/LINUX-LIBRARY-NOTICES.txt"
    runtime_license_dir="repo-notices/$platform/licenses"

    mapfile -t elf_paths < <(
        find "$package_dir" -type f -print0 |
            while IFS= read -r -d '' candidate; do
                if readelf -h "$candidate" >/dev/null 2>&1; then
                    printf '%s\n' "$candidate"
                fi
            done |
            sort
    )

    if [[ ${#elf_paths[@]} -eq 0 ]]; then
        echo "No ELF binaries were found under $package_dir." >&2
        exit 1
    fi

    declare -A package_versions=()
    declare -A package_urls=()
    declare -A package_license_files=()
    declare -A package_libraries=()
    declare -A seen_library_paths=()
    local resolved_library_pattern='=>[[:space:]](/[^[:space:]]+)'
    local direct_library_pattern='^[[:space:]]*(/[^[:space:]]+)'

    printf 'binary\tlibrary\tpackage\tpackage_version\tlicense_file\thomepage\n' > "$runtime_inventory_file"

    for elf_path in "${elf_paths[@]}"; do
        while IFS= read -r line; do
            library_path=""
            if [[ "$line" =~ $resolved_library_pattern ]]; then
                library_path="${BASH_REMATCH[1]}"
            elif [[ "$line" =~ $direct_library_pattern ]]; then
                library_path="${BASH_REMATCH[1]}"
            else
                continue
            fi

            library_path="$(readlink -f "$library_path")"
            if [[ ! -f "$library_path" || -n "${seen_library_paths[$library_path]:-}" ]]; then
                continue
            fi
            seen_library_paths["$library_path"]=1

            runtime_package_name="$(dpkg-query -S "$library_path" 2>/dev/null | awk -F ': ' 'NR == 1 { print $1; exit }')"
            if [[ -z "$runtime_package_name" ]]; then
                continue
            fi

            if [[ -z "${package_versions[$runtime_package_name]:-}" ]]; then
                package_versions["$runtime_package_name"]="$(dpkg-query -W -f='${Version}' "$runtime_package_name" 2>/dev/null || true)"
                package_urls["$runtime_package_name"]="$(linux_package_field "$runtime_package_name" 'Homepage')"

                doc_package="${runtime_package_name%%:*}"
                license_source="/usr/share/doc/$doc_package/copyright"
                if [[ -f "$license_source" ]]; then
                    mkdir -p "$licenses_dir/$doc_package"
                    cp "$license_source" "$licenses_dir/$doc_package/copyright"
                    package_license_files["$runtime_package_name"]="repo-notices/$platform/licenses/$doc_package/copyright"
                else
                    package_license_files["$runtime_package_name"]=""
                fi
            fi

            library_name="$(basename "$library_path")"
            if [[ -n "${package_libraries[$runtime_package_name]:-}" ]]; then
                package_libraries["$runtime_package_name"]+=", "
            fi
            package_libraries["$runtime_package_name"]+="$library_name"

            printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
                "$(basename "$elf_path")" \
                "$library_name" \
                "$runtime_package_name" \
                "${package_versions[$runtime_package_name]:-}" \
                "${package_license_files[$runtime_package_name]:-}" \
                "${package_urls[$runtime_package_name]:-}" \
                >> "$runtime_inventory_file"
        done < <(ldd "$elf_path" 2>/dev/null || true)
    done

    {
        echo "Linked runtime library notice summary"
        echo
        echo "Generated from ELF dependencies resolved from the packaged files under $artifact_package_name."
        echo
        while IFS= read -r runtime_package; do
            [[ -n "$runtime_package" ]] || continue
            echo "Package: $runtime_package"
            echo "Version: ${package_versions[$runtime_package]:-unknown}"
            echo "Homepage: ${package_urls[$runtime_package]:-unknown}"
            echo "Libraries: ${package_libraries[$runtime_package]:-none}"
            if [[ -n "${package_license_files[$runtime_package]:-}" ]]; then
                echo "License files: ${package_license_files[$runtime_package]}"
            else
                echo "License files: missing"
            fi
            echo
        done < <(printf '%s\n' "${!package_versions[@]}" | sort)
    } > "$runtime_summary_file"

    while IFS= read -r runtime_package; do
        [[ -n "$runtime_package" ]] || continue
        append_package \
            "$(spdx_id "linux-$runtime_package")" \
            "$runtime_package" \
            "NOASSERTION" \
            "NOASSERTION" \
            "${package_versions[$runtime_package]:-}" \
            "Debian runtime package providing linked shared libraries: ${package_libraries[$runtime_package]:-none}." \
            "${package_license_files[$runtime_package]:-missing}" \
            "License text copied from the runner image into ${package_license_files[$runtime_package]:-missing}." \
            "${package_urls[$runtime_package]:-}" \
            ""
    done < <(printf '%s\n' "${!package_versions[@]}" | sort)
}

pacman_field() {
    local package_name="$1"
    local field_name="$2"

    pacman -Si "$package_name" 2>/dev/null | awk -F ':' -v wanted="$field_name" '
        $1 ~ "^[[:space:]]*" wanted "[[:space:]]*$" {
            sub(/^[[:space:]]+/, "", $2)
            print $2
            exit
        }
    '
}

collect_windows_runtime_notices() {
    if ! command -v pacman >/dev/null 2>&1; then
        echo "This script requires pacman for Windows simulator notice generation." >&2
        exit 1
    fi

    runtime_inventory_file="$notice_root/WINDOWS-RUNTIME-DLLS.txt"
    runtime_summary_file="$notice_root/WINDOWS-DLL-NOTICES.txt"
    runtime_license_dir="repo-notices/$platform/licenses"

    mapfile -t dll_paths < <(find "$package_dir" -maxdepth 1 -type f -name '*.dll' | sort)
    if [[ ${#dll_paths[@]} -eq 0 ]]; then
        echo "No DLLs were found under $package_dir." >&2
        exit 1
    fi

    declare -A package_versions=()
    declare -A package_licenses=()
    declare -A package_urls=()
    declare -A package_descriptions=()
    declare -A package_license_dirs=()
    declare -A package_dlls=()
    declare -A dll_owners=()
    declare -A dll_owner_versions=()

    resolve_windows_source_path() {
        local packaged_path="$1"
        local rel_path="${packaged_path#$package_dir/}"

        if [[ -f "$MINGW_PREFIX/$rel_path" ]]; then
            printf '%s\n' "$MINGW_PREFIX/$rel_path"
            return 0
        fi

        if [[ "$rel_path" != */* && -f "$MINGW_PREFIX/bin/$rel_path" ]]; then
            printf '%s\n' "$MINGW_PREFIX/bin/$rel_path"
            return 0
        fi

        return 1
    }

    package_version_from_name() {
        local package_name="$1"

        pacman -Q "$package_name" 2>/dev/null | awk 'NR == 1 { print $2; exit }'
    }

    ensure_windows_package_metadata() {
        local runtime_package_name="$1"
        local package_version="$2"

        if [[ "$runtime_package_name" == "unknown" ]]; then
            return 0
        fi

        if [[ -z "$package_version" ]]; then
            package_version="$(package_version_from_name "$runtime_package_name")"
        fi

        if [[ -n "${package_versions[$runtime_package_name]:-}" ]]; then
            if [[ -z "${package_versions[$runtime_package_name]}" && -n "$package_version" ]]; then
                package_versions["$runtime_package_name"]="$package_version"
            fi
            return 0
        fi

        package_versions["$runtime_package_name"]="$package_version"
        package_licenses["$runtime_package_name"]="$(pacman_field "$runtime_package_name" 'Licenses')"
        package_urls["$runtime_package_name"]="$(pacman_field "$runtime_package_name" 'URL')"
        package_descriptions["$runtime_package_name"]="$(pacman_field "$runtime_package_name" 'Description')"

        license_source_dir="$MINGW_PREFIX/share/licenses/$runtime_package_name"
        if [[ -d "$license_source_dir" ]]; then
            mkdir -p "$licenses_dir/$runtime_package_name"
            cp -R "$license_source_dir"/. "$licenses_dir/$runtime_package_name"/
            package_license_dirs["$runtime_package_name"]="repo-notices/$platform/licenses/$runtime_package_name"
        else
            package_license_dirs["$runtime_package_name"]=""
        fi
    }

    append_package_dll() {
        local runtime_package_name="$1"
        local dll_name="$2"

        if [[ "$runtime_package_name" == "unknown" ]]; then
            return 0
        fi

        if [[ "${package_dlls[$runtime_package_name]:-}" == "non-DLL runtime files" || -z "${package_dlls[$runtime_package_name]:-}" ]]; then
            package_dlls["$runtime_package_name"]="$dll_name"
        else
            package_dlls["$runtime_package_name"]+=", $dll_name"
        fi
    }

    mapfile -t runtime_packages < <(
        find "$package_dir" \
            \( -path "$package_dir/repo-notices" -o -path "$package_dir/repo-notices/*" \) -prune -o \
            -type f -print0 |
        while IFS= read -r -d '' packaged_path; do
            source_path="$(resolve_windows_source_path "$packaged_path" || true)"
            [[ -n "$source_path" ]] || continue
            printf '%s\0' "$source_path"
        done |
        xargs -0 -r pacman -Qqo 2>/dev/null |
        sort -u
    )

    while IFS= read -r runtime_package; do
        [[ -n "$runtime_package" ]] || continue
        ensure_windows_package_metadata "$runtime_package" ""
        if [[ -z "${package_dlls[$runtime_package]:-}" ]]; then
            package_dlls["$runtime_package"]="non-DLL runtime files"
        fi
    done < <(printf '%s\n' "${runtime_packages[@]}")

    printf 'dll\tpackage\tpackage_version\tlicenses\tlicense_dir\n' > "$runtime_inventory_file"

    for dll_path in "${dll_paths[@]}"; do
        dll_name="$(basename "$dll_path")"
        source_path="$(resolve_windows_source_path "$dll_path" || true)"
        runtime_package_name="unknown"
        package_version=""

        if [[ -n "$source_path" ]]; then
            runtime_package_name="$(pacman -Qqo "$source_path" 2>/dev/null | awk 'NR == 1 { print; exit }')"
            if [[ -n "$runtime_package_name" ]]; then
                package_version="$(package_version_from_name "$runtime_package_name")"
            else
                runtime_package_name="unknown"
            fi
        fi

        dll_owners["$dll_name"]="$runtime_package_name"
        dll_owner_versions["$dll_name"]="$package_version"

        ensure_windows_package_metadata "$runtime_package_name" "$package_version"
        append_package_dll "$runtime_package_name" "$dll_name"

        printf '%s\t%s\t%s\t%s\t%s\n' \
            "$dll_name" \
            "$runtime_package_name" \
            "$package_version" \
            "${package_licenses[$runtime_package_name]:-}" \
            "${package_license_dirs[$runtime_package_name]:-}" \
            >> "$runtime_inventory_file"
    done

    {
        echo "Bundled runtime notice summary"
        echo
        echo "Generated from MSYS2-managed runtime files resolved from the packaged Windows artifact under $artifact_package_name."
        echo
        while IFS= read -r runtime_package; do
            [[ -n "$runtime_package" ]] || continue
            echo "Package: $runtime_package"
            echo "Version: ${package_versions[$runtime_package]:-unknown}"
            echo "Licenses: ${package_licenses[$runtime_package]:-unknown}"
            echo "URL: ${package_urls[$runtime_package]:-unknown}"
            echo "Description: ${package_descriptions[$runtime_package]:-unknown}"
            echo "Packaged runtime files: ${package_dlls[$runtime_package]:-none}"
            if [[ -n "${package_license_dirs[$runtime_package]:-}" ]]; then
                echo "License files: ${package_license_dirs[$runtime_package]}"
            else
                echo "License files: missing"
            fi
            echo
        done < <(printf '%s\n' "${!package_versions[@]}" | sort)
    } > "$runtime_summary_file"

    while IFS= read -r runtime_package; do
        [[ -n "$runtime_package" ]] || continue
        append_package \
            "$(spdx_id "msys2-$runtime_package")" \
            "$runtime_package" \
            "${package_urls[$runtime_package]:-NOASSERTION}" \
            "NOASSERTION" \
            "${package_versions[$runtime_package]:-}" \
            "MSYS2 package providing bundled Windows runtime files: ${package_dlls[$runtime_package]:-none}." \
            "${package_licenses[$runtime_package]:-unknown}" \
            "License files copied to ${package_license_dirs[$runtime_package]:-missing}." \
            "${package_urls[$runtime_package]:-}" \
            "pkg:generic/$runtime_package@${package_versions[$runtime_package]:-unknown}"
    done < <(printf '%s\n' "${!package_versions[@]}" | sort)

    for dll_path in "${dll_paths[@]}"; do
        dll_name="$(basename "$dll_path")"
        append_package \
            "$(spdx_id "windows-dll-$dll_name")" \
            "$dll_name" \
            "NOASSERTION" \
            "NOASSERTION" \
            "${dll_owner_versions[$dll_name]:-}" \
            "Bundled runtime DLL copied into the packaged Windows simulator artifact." \
            "" \
            "Owned by ${dll_owners[$dll_name]:-unknown}." \
            "" \
            ""
    done
}

write_notice_summary() {
    cat > "$notice_root/NOTICE-SUMMARY.txt" <<EOF
Simulator notice bundle for the $platform GitHub CI artifact.

Package source provenance:
- upstream_url=$upstream_source_repository_url
- upstream_commit=$upstream_source_commit
- xlsxio_url=${xlsxio_source_repository_url:-unknown}
- xlsxio_commit=${xlsxio_source_commit:-unknown}

Included notice files:
- COPYING
- LICENSE.txt
- repo-notices/$platform/gpl/COPYING.txt
- repo-notices/$platform/decnumbericu/ICU-license.html
- repo-notices/$platform/xlsxio/LICENSE.txt
- ${runtime_inventory_file#"$package_dir/"}
- ${runtime_summary_file#"$package_dir/"}

Runtime package notice directory:
- $runtime_license_dir
EOF
}

: > "$tmp_dir/describes.json"
: > "$tmp_dir/packages.json"

write_common_notice_files
append_common_packages

case "$platform" in
    linux)
        collect_linux_runtime_notices
        ;;
    windows)
        collect_windows_runtime_notices
        ;;
esac

write_notice_summary

{
    echo '{'
    echo '  "spdxVersion": "SPDX-2.3",'
    echo '  "dataLicense": "CC0-1.0",'
    echo '  "SPDXID": "SPDXRef-DOCUMENT",'
    printf '  "name": "R47 %s simulator third-party inventory",\n' "$platform"
    printf '  "documentNamespace": "https://r47.invalid/spdx/%s/%s",\n' "$(json_escape "$platform")" "$(json_escape "$upstream_source_commit")"
    echo '  "creationInfo": {'
    printf '    "created": "%s",\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    echo '    "creators": ['
    echo '      "Tool: generate_simulator_notice_artifacts.sh"'
    echo '    ]'
    echo '  },'
    echo '  "documentDescribes": ['
    cat "$tmp_dir/describes.json"
    echo
    echo '  ],'
    echo '  "packages": ['
    cat "$tmp_dir/packages.json"
    echo
    echo '  ]'
    echo '}'
} > "$spdx_file"