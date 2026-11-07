#!/bin/bash
# Print one value of a BUILD-METADATA.txt, refused unless it has the shape its kind
# allows. The release lane's build job writes that file, and the same job compiles and
# runs upstream code, so a job that holds a write token or OIDC reads it as untrusted
# input: a value it does arithmetic on, or uses as a path, would run what the file says.
#
# Usage: read_build_metadata.sh <file> <key> <kind>
# Kinds: name (a file name), commit (40 hex digits), sha256 (64 hex digits),
# url (https, no spaces or quotes), version-name (major.minor.patch),
# dev-version-name (major.minor.patch-run.attempt),
# version-code (digits), text (letters, digits, spaces, and . _ ( ) + -, or empty).
set -Eeuo pipefail

[ "$#" -eq 3 ] || {
    echo "usage: $0 <file> <key> <kind>" >&2
    exit 2
}
file="$1"
key="$2"
kind="$3"

case "$kind" in
    name) pattern='^[A-Za-z0-9][A-Za-z0-9._+-]*$' ;;
    commit) pattern='^[0-9a-f]{40}$' ;;
    sha256) pattern='^[0-9a-f]{64}$' ;;
    url) pattern='^https://[A-Za-z0-9._~/%-]+$' ;;
    version-name) pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' ;;
    dev-version-name) pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-[0-9]+\.[0-9]+$' ;;
    version-code) pattern='^(0|[1-9][0-9]{0,8})$' ;;
    text) pattern='^[A-Za-z0-9 ._()+-]*$' ;;
    *)
        echo "unknown kind '$kind'" >&2
        exit 2
        ;;
esac

[ -f "$file" ] || {
    echo "no metadata file at $file" >&2
    exit 1
}
lines="$(grep -c "^${key}=" "$file" || true)"
[ "$lines" -eq 1 ] || {
    echo "$file holds $lines lines of $key, not one" >&2
    exit 1
}
values="$(sed -n "s/^${key}=//p" "$file")"
[[ "$values" =~ $pattern ]] || {
    echo "$file holds a $key that is not a $kind: refused" >&2
    exit 1
}
printf '%s\n' "$values"
