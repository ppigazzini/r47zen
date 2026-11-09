#!/bin/bash
# Fail unless a native library exports only its JNI entry points.
#
# libr47zen.so links with r47zen/exports.map, which keeps JNI_OnLoad and the
# Java_ names global and makes every other symbol local. A library built
# without it exported 3,911 symbols in 0.2.0, each a lookup at every load and a
# name another library could collide with. A defined, non-local symbol in the
# dynamic table is an export.
#
# Usage: check_native_exports.sh <library.so>
#   READELF names the readelf to use (llvm-readelf or GNU readelf); default readelf.

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "usage: $0 <library.so>" >&2
    exit 2
fi
readelf_tool="${READELF:-readelf}"
symbols="$("$readelf_tool" --dyn-syms -W "$1")" || {
    echo "$readelf_tool could not read $1." >&2
    exit 1
}
# Num: Value Size Type Bind Vis Ndx Name
others="$(awk '$1 ~ /^[0-9]+:$/ && $5 != "LOCAL" && $7 != "UND" && NF >= 8 {
    name = $8; sub(/@.*/, "", name)
    if (name != "JNI_OnLoad" && name !~ /^Java_/) print name
}' <<<"$symbols")"
if [[ -n "$others" ]]; then
    echo "$1 exports $(grep -c . <<<"$others") symbols beyond its JNI entry points, such as:" >&2
    head -5 <<<"$others" | sed 's/^/  /' >&2
    exit 1
fi
echo "$1 exports only JNI_OnLoad and Java_ entry points."
