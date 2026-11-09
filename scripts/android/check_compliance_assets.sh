#!/bin/bash
# Fail unless an unpacked APK or AAB carries every compliance asset the build
# packages, and copy them into the evidence directory.
#
# preBuild packages four files into every variant's assets: COPYING (the GPL),
# LICENSE.txt (xlsxio's license), SOURCE (where the source of these bytes is),
# and THIRD-PARTY.spdx.json. An artifact that lost one ships without its
# license notice or its source pointer, so the evidence must say so and stop,
# not write a stand-in of its own. repo-notices/ comes along when
# present. The SPDX inventory must also pass r47_contracts/spdx_inventory.py,
# which reads Google's Maven groups from android/settings.gradle: each module
# located in the repository it came from, with its checksum and license, every
# linked component present, and every package related to the app.
#
# Usage: check_compliance_assets.sh <unpacked dir> <asset root> <evidence dir>
#   <asset root> is assets for an APK and base/assets for an AAB. Writes
#   <evidence dir>/compliance-assets/ and PACKAGED-COMPLIANCE-ASSETS.txt.

set -euo pipefail

if [[ "$#" -ne 3 ]]; then
    echo "usage: $0 <unpacked dir> <asset root> <evidence dir>" >&2
    exit 2
fi
unpack_dir="$1"
asset_root="$2"
destination_dir="$3"
compliance_dir="$destination_dir/compliance-assets"

missing=()
for asset_name in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json; do
    if [[ ! -s "$unpack_dir/$asset_root/$asset_name" ]]; then
        missing+=("$asset_root/$asset_name")
    fi
done
if [[ "${#missing[@]}" -gt 0 ]]; then
    echo "The artifact lacks compliance assets the build packages: ${missing[*]}" >&2
    exit 1
fi

mkdir -p "$compliance_dir"
for asset_name in COPYING LICENSE.txt SOURCE THIRD-PARTY.spdx.json; do
    cp "$unpack_dir/$asset_root/$asset_name" "$compliance_dir/$asset_name"
done
if [[ -d "$unpack_dir/$asset_root/repo-notices" ]]; then
    rm -rf "$compliance_dir/repo-notices"
    cp -R "$unpack_dir/$asset_root/repo-notices" "$compliance_dir/repo-notices"
fi
python3 "$(dirname "$0")/../r47_contracts/spdx_inventory.py" \
    "$compliance_dir/THIRD-PARTY.spdx.json" "$(dirname "$0")/../../android/settings.gradle"
find "$compliance_dir" -type f | sed "s#^$destination_dir/##" | sort >"$destination_dir/PACKAGED-COMPLIANCE-ASSETS.txt"
