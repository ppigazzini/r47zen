#!/bin/bash
# Refuse a shipped native library that exports a test entry point.
#
# The program-load test bridge (Java_..._ProgramLoadTestBridge_*, r47_test_bridge_*)
# resets, injects, and overrides file descriptors; it is compiled only for the
# instrumented lanes, by a Gradle property and a CMake option, and nothing read
# the built library to prove it stayed out. A `main` is a program's entry
# point, which a library has no use for: the app once exported upstream's
# developer tool's. Both are checked in the dynamic symbol table with
# GNU readelf, which the signing jobs have from the runner image.
#
# Usage: check_shipped_symbols.sh <library.so>...

set -euo pipefail

if [[ "$#" -eq 0 ]]; then
    echo "usage: $0 <library.so>..." >&2
    exit 2
fi
command -v readelf >/dev/null 2>&1 || {
    echo "readelf (binutils) is not on PATH; the exported-symbol check needs it." >&2
    exit 1
}

status=0
for library in "$@"; do
    if ! symbols="$(readelf --dyn-syms -W "$library")"; then
        echo "readelf could not read ${library}." >&2
        exit 1
    fi
    # Num: Value Size Type Bind Vis Ndx Name; a defined, non-local symbol is exported.
    exported_test="$(awk '$5 != "LOCAL" && $7 != "UND" && NF >= 8 {
        name = $8; sub(/@.*/, "", name)
        if (name ~ /ProgramLoadTestBridge/ || name ~ /^r47_test_bridge/ || name == "main") print name
    }' <<<"$symbols")"
    if [[ -n "$exported_test" ]]; then
        echo "${library} exports what only a test or a program has:" >&2
        sed 's/^/  /' <<<"$exported_test" >&2
        status=1
    fi
done
exit "$status"
