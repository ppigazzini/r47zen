#!/bin/bash
# Print the assets a re-cut leaves behind on a release tag, one name per line.
#
# Every published file is named after both short commits, and the release
# action's overwrite replaces only an asset of the same name, so a re-cut whose
# overlay or core moved adds a second APK, AAB, and evidence pair beside the
# first. The next release then finds two evidence archives and refuses.
# publish-production-release deletes what this prints, after its upload, never
# before: a delete first would leave the tag with no evidence when the upload
# fails, and every later dispatch refuses that too.
#
# It prints nothing unless the release already holds every file just uploaded,
# so a partial upload deletes nothing.
#
# Usage: stale_release_assets.sh --assets-json <file> <uploaded name>...
#   <file> holds `gh release view <tag> --json assets`.

set -euo pipefail

if [[ "${1-}" != --assets-json || -z "${2-}" || "$#" -lt 3 ]]; then
    echo "usage: $0 --assets-json <file> <uploaded name>..." >&2
    exit 2
fi
assets_json="$2"
shift 2

python3 - "$assets_json" "$@" <<'EOF'
import json
import sys

assets = [a["name"] for a in json.load(open(sys.argv[1])).get("assets") or []]
uploaded = set(sys.argv[2:])
missing = uploaded - set(assets)
if missing:
    sys.stderr.write(
        "The release lacks uploaded files " + ", ".join(sorted(missing)) + "; deleting nothing.\n"
    )
    sys.exit(1)
for name in assets:
    if name not in uploaded:
        print(name)
EOF
