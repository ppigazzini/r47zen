#!/bin/bash

# Print the upstream C47 core's own version, the VERSION1 string in
# src/c47/defines.h (for example 00.109.05.00b2), or nothing when the file is
# absent or the define is not one quoted token of [0-9A-Za-z.-]. The value
# names the firmware family; the upstream commit names the exact build, so a
# caller shows both and falls back to the commit alone on empty output. It
# never fails a build: upstream owns the file and may reshape the define.
#
# Usage: scripts/android/read_upstream_version.sh <path to defines.h>

set -Eeuo pipefail

defines_file="${1-}"
if [[ -z "$defines_file" || ! -f "$defines_file" ]]; then
    exit 0
fi

# The second sed reads all input, so the first never dies of SIGPIPE.
value="$(sed -n 's/^[[:space:]]*#[[:space:]]*define[[:space:]]\{1,\}VERSION1[[:space:]]\{1,\}"\([^"]*\)".*/\1/p' "$defines_file" | sed -n '1p')"

if [[ "$value" =~ ^[0-9A-Za-z.-]{1,40}$ ]]; then
    printf '%s\n' "$value"
fi
