#!/bin/bash

set -Eeuo pipefail

# Bundle the stripped Windows simulator runtime and its GTK dependency closure
# for the windows-ci artifact. Runs under the MSYS2 shell on windows-latest.
#
# Required environment (set by the windows-ci "Bundle stripped runtime" step):
#   ARTIFACT_SUFFIX  matrix artifact suffix used for the artifact directory name
#   MSYSTEM_LABEL    matrix MSYS2 system label recorded in the build metadata
#   UPSTREAM_URL     resolved upstream repository URL
#   UPSTREAM_COMMIT  resolved upstream commit SHA
#   XLSXIO_URL       xlsxio toolchain source URL
#   XLSXIO_COMMIT    xlsxio toolchain source commit
#   MINGW_PREFIX     MSYS2 runtime prefix (set by the MSYS2 shell)
#   GITHUB_OUTPUT    step output file (set by GitHub Actions)
: "${ARTIFACT_SUFFIX:?ARTIFACT_SUFFIX must be set}"
: "${MSYSTEM_LABEL:?MSYSTEM_LABEL must be set}"
: "${UPSTREAM_URL:?UPSTREAM_URL must be set}"
: "${UPSTREAM_COMMIT:?UPSTREAM_COMMIT must be set}"
: "${XLSXIO_URL:?XLSXIO_URL must be set}"
: "${XLSXIO_COMMIT:?XLSXIO_COMMIT must be set}"
: "${MINGW_PREFIX:?MINGW_PREFIX must be set}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT must be set}"

artifact_dir="artifact-${ARTIFACT_SUFFIX}"
release_dir="$artifact_dir/release"
package_dir="$release_dir/c47-windows"
stripped_count=0

rm -rf "$artifact_dir"
mkdir -p "$release_dir"
unzip -q c47-windows.zip -d "$release_dir"

if command -v llvm-strip > /dev/null 2>&1; then
  strip_tool="llvm-strip"
elif command -v strip > /dev/null 2>&1; then
  strip_tool="strip"
else
  echo "No strip tool found in PATH." >&2
  exit 1
fi

"$strip_tool" "$package_dir/c47.exe" "$package_dir/r47.exe"
stripped_count=2

copy_runtime_path() {
  local rel_path="$1"
  local source_path="$MINGW_PREFIX/$rel_path"
  local dest_path="$package_dir/$rel_path"

  if [[ ! -e "$source_path" ]]; then
    echo "Missing GTK runtime path: $source_path" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$dest_path")"
  cp -R "$source_path" "$dest_path"
}

copy_runtime_file() {
  local source_path="$1"
  local dest_path="$2"

  if [[ ! -f "$source_path" ]]; then
    echo "Missing GTK runtime file: $source_path" >&2
    exit 1
  fi

  mkdir -p "$(dirname "$dest_path")"

  if [[ ! -f "$dest_path" ]]; then
    cp "$source_path" "$dest_path"
  fi
}

copy_runtime_tool() {
  local tool_name="$1"
  local source_path="$MINGW_PREFIX/bin/$tool_name"

  copy_runtime_file "$source_path" "$package_dir/$tool_name"
}

copy_runtime_dll() {
  local source_path="$1"

  copy_runtime_file "$source_path" "$package_dir/$(basename "$source_path")"
}

resolve_runtime_dlls() {
  find "$package_dir" -type f \( -name '*.exe' -o -name '*.dll' \) -print0 \
    | xargs -0 -r ldd \
    | awk -v prefix="$MINGW_PREFIX/bin/" '
        $3 ~ ("^" prefix) && $3 ~ /\.dll$/ { print $3 }
        $1 ~ ("^" prefix) && $1 ~ /\.dll$/ { print $1 }
      ' \
    | sort -u
}

runtime_paths=(
  "etc/gtk-3.0"
  "lib/gdk-pixbuf-2.0"
  "lib/gio/modules"
  "lib/gtk-3.0"
  "share/glib-2.0/schemas"
  "share/gtk-3.0"
  "share/icons/Adwaita"
  "share/icons/hicolor"
  "share/mime"
  "share/themes"
)

for rel_path in "${runtime_paths[@]}"; do
  copy_runtime_path "$rel_path"
done

runtime_tools=(
  "gdk-pixbuf-query-loaders.exe"
  "gio-querymodules.exe"
  "glib-compile-schemas.exe"
  "gtk-query-immodules-3.0.exe"
)

for tool_name in "${runtime_tools[@]}"; do
  copy_runtime_tool "$tool_name"
done

previous_dlls=""
while :; do
  dlls="$(resolve_runtime_dlls)"

  if [[ -z "$dlls" ]]; then
    echo "Failed to resolve runtime DLLs from $MINGW_PREFIX/bin." >&2
    exit 1
  fi

  if [[ "$dlls" == "$previous_dlls" ]]; then
    break
  fi

  previous_dlls="$dlls"

  while IFS= read -r dll; do
    [[ -n "$dll" ]] || continue
    copy_runtime_dll "$dll"
  done <<< "$dlls"
done

"$package_dir/glib-compile-schemas.exe" "$package_dir/share/glib-2.0/schemas"
"$package_dir/gio-querymodules.exe" "$package_dir/lib/gio/modules"

# Stage the repo-owned launchers so hosted artifacts inherit the
# default portrait startup contract from .github/project/.
cp ./.github/project/windows-launchers/c47.cmd "$package_dir/c47.cmd"
cp ./.github/project/windows-launchers/r47.cmd "$package_dir/r47.cmd"

printf 'Stripped executable sizes:\n'
stat -c '%n %s' "$package_dir/c47.exe" "$package_dir/r47.exe"

bash ./scripts/package-notices/generate_simulator_notice_artifacts.sh \
  --platform windows \
  --package-dir "$package_dir" \
  --upstream-source-repository-url "$UPSTREAM_URL" \
  --upstream-source-commit "$UPSTREAM_COMMIT" \
  --xlsxio-source-repository-url "$XLSXIO_URL" \
  --xlsxio-source-commit "$XLSXIO_COMMIT"

find "$package_dir" -type f | sed "s#^$release_dir/##" | sort > "$release_dir/PACKAGE-CONTENTS.txt"

cat > "$release_dir/BUILD-METADATA.txt" <<EOF
upstream_url=$UPSTREAM_URL
upstream_commit=$UPSTREAM_COMMIT
upstream_license_file=COPYING
source_manifest_file=SOURCE
xlsxio_url=$XLSXIO_URL
xlsxio_commit=$XLSXIO_COMMIT
xlsxio_license_file=LICENSE.txt
make_target=dist_windows
msystem=$MSYSTEM_LABEL
strip_tool=$strip_tool
stripped_files_count=$stripped_count
package_zip=c47-windows.zip
package_dir=$(basename "$package_dir")
third_party_inventory_file=THIRD-PARTY.spdx.json
platform_notice_root=repo-notices/windows
platform_notice_summary_file=repo-notices/windows/NOTICE-SUMMARY.txt
gpl_notice_file=repo-notices/windows/gpl/COPYING.txt
decnumbericu_notice_file=repo-notices/windows/decnumbericu/ICU-license.html
mini_gmp_notice_file=repo-notices/windows/mini-gmp/NOTICE.txt
xlsxio_notice_file=repo-notices/windows/xlsxio/LICENSE.txt
windows_gtk_runtime_dirs=etc/gtk-3.0,lib/gdk-pixbuf-2.0,lib/gio/modules,lib/gtk-3.0,share/glib-2.0/schemas,share/gtk-3.0,share/icons/Adwaita,share/icons/hicolor,share/mime,share/themes
windows_gtk_runtime_tools=glib-compile-schemas.exe,gio-querymodules.exe,gtk-query-immodules-3.0.exe,gdk-pixbuf-query-loaders.exe
windows_runtime_launcher_files=c47.cmd,r47.cmd
windows_runtime_launcher_default_args=--portrait
windows_dll_inventory_file=repo-notices/windows/WINDOWS-RUNTIME-DLLS.txt
windows_dll_notice_summary_file=repo-notices/windows/WINDOWS-DLL-NOTICES.txt
windows_dll_notice_dir=repo-notices/windows/licenses
EOF

if [[ -f "$package_dir/repo-notices/windows/jimtcl/LICENSE.txt" ]]; then
  jimtcl_url=$(sed -n 's/^jimtcl_url=//p' "$package_dir/SOURCE")
  jimtcl_commit=$(sed -n 's/^jimtcl_commit=//p' "$package_dir/SOURCE")
  if [[ -n "$jimtcl_url" ]]; then
    echo "jimtcl_url=$jimtcl_url" >> "$release_dir/BUILD-METADATA.txt"
  fi
  if [[ -n "$jimtcl_commit" ]]; then
    echo "jimtcl_commit=$jimtcl_commit" >> "$release_dir/BUILD-METADATA.txt"
  fi
  echo "jimtcl_notice_file=repo-notices/windows/jimtcl/LICENSE.txt" >> "$release_dir/BUILD-METADATA.txt"
fi

rm -f c47-windows.zip

echo "artifact_path=$release_dir" >> "$GITHUB_OUTPUT"
