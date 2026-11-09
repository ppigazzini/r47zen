#!/bin/bash
# Print the versions of the host tools that generate code the app ships, on one
# line, for the build job to hand to the packaging evidence.
#
# Upstream's generators, built by Meson and Ninja with the host C compiler, write
# sources the library compiles: ttf2RasterFonts renders the LCD fonts with
# FreeType (FT_LOAD_RENDER) into generated/rasterFontsData.c. The Linux build
# dependencies take whatever the runner image's archive holds, so a FreeType
# update could change shipped glyph bitmaps with no commit here; the evidence
# records which versions built each artifact.
#
# Output: meson <v> ninja <v> <gcc|clang> <v> <libfreetype6|freetype2> <v>
# Each version keeps only [A-Za-z0-9._+-], the evidence's text kind; a tool that
# is absent prints "absent".

set -euo pipefail

clean() { tr -c 'A-Za-z0-9._+-' '-' <<<"$1" | sed 's/-*$//'; }

version_of() {
    local tool="$1"
    shift
    if command -v "$tool" >/dev/null 2>&1; then
        clean "$("$tool" "$@" 2>/dev/null | head -n 1)"
    else
        echo absent
    fi
}

compiler=gcc
cc_banner="$(cc --version 2>/dev/null || true)"
if [[ "${cc_banner%%$'\n'*}" == *[Cc]lang* ]]; then
    compiler=clang
fi

if command -v dpkg-query >/dev/null 2>&1 &&
    freetype="$(dpkg-query -W -f='${Version}' libfreetype6 2>/dev/null)" && [[ -n "$freetype" ]]; then
    freetype_name=libfreetype6
else
    freetype_name=freetype2
    freetype="$(pkg-config --modversion freetype2 2>/dev/null || echo absent)"
fi

echo "meson $(version_of meson --version) ninja $(version_of ninja --version)" \
    "${compiler} $(version_of cc -dumpfullversion) ${freetype_name} $(clean "$freetype")"
