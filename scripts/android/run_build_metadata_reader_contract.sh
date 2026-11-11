#!/bin/bash

# Contract: a job that holds a write token, OIDC, or a signing key reads the build
# job's BUILD-METADATA.txt only through scripts/android/read_build_metadata.sh. That
# job compiles and runs upstream code, so its metadata is untrusted input, and a value
# a later job does arithmetic on, or uses as a path, would run what the file says.
#
# - The reader prints the real values of the 0.2.0 release and of a dev build.
# - It refuses, and never evaluates, a value of the wrong shape, a key that is missing
#   or given twice, and a kind it does not know.
# - collect_packaging_evidence.sh writes an upstream_version the reader takes, empty
#   for a core with no release tag included, and refuses one of another shape.
# - No workflow reads a BUILD-METADATA.txt key with a bare sed.
#
# Host test; the writer's check builds a fixture bundle with cc and zip.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

READER="$PROJECT_ROOT/scripts/android/read_build_metadata.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

read_value() { bash "$READER" "$fixture/BUILD-METADATA.txt" "$1" "$2" 2>/dev/null; }

cat >"$fixture/BUILD-METADATA.txt" <<'EOF'
artifact_name=r47zen_0.2.0_2a664c67_ada9fe0f.aab
upstream_commit=2a664c67badc6c7cc117a658a47d30d9bec64789
upstream_url=https://gitlab.com/rpncalculators/c43.git
upstream_version=00.109.05.00b2
version_name=0.2.0
version_code=12
artifact_signing_cert_sha256=fa001b0b6a847335806c5ba5094a07c38b1962b0b983e1b07407bbe5628aacc6
dev_name=0.3.0-85.1
empty_text=
EOF
for pair in "artifact_name name" "upstream_commit commit" "upstream_url url" \
    "upstream_version text" "version_name version-name" "version_code version-code" \
    "artifact_signing_cert_sha256 sha256" "dev_name dev-version-name" "empty_text text"; do
    read -r key kind <<<"$pair"
    expected="$(sed -n "s/^${key}=//p" "$fixture/BUILD-METADATA.txt")"
    actual="$(read_value "$key" "$kind")" || contract_fail "the reader refused a real $key."
    [ "$actual" = "$expected" ] || contract_fail "the reader changed $key: '$actual'."
done

marker="$fixture/evaluated"
# shellcheck disable=SC2016 # each hostile value must reach the reader unexpanded
hostile=(
    "version_code version-code a[\$(touch $marker)]"
    "version_code version-code 12+1"
    "version_name version-name 0.2.0\$(touch $marker)"
    "artifact_name name ../../release.apk"
    "artifact_name name -rf"
    "upstream_commit commit 2a664c67"
    "upstream_url url https://example.com/\$(touch $marker)"
    "upstream_version text 1\`touch $marker\`"
    "artifact_signing_cert_sha256 sha256 FA001B"
)
for entry in "${hostile[@]}"; do
    read -r key kind value <<<"$entry"
    printf '%s=%s\n' "$key" "$value" >"$fixture/BUILD-METADATA.txt"
    if read_value "$key" "$kind" >/dev/null; then
        contract_fail "the reader took $key=$value as a $kind."
    fi
    version_code="$(read_value version_code version-code || true)"
    : "$((${version_code:-0} + 1))"
done
[ ! -e "$marker" ] || contract_fail "a hostile value ran a command."

printf 'version_code=12\nversion_code=13\n' >"$fixture/BUILD-METADATA.txt"
! read_value version_code version-code >/dev/null || contract_fail "the reader took a key given twice."
printf 'version_name=0.2.0\n' >"$fixture/BUILD-METADATA.txt"
! read_value version_code version-code >/dev/null || contract_fail "the reader took a missing key."
! read_value version_name nonsense >/dev/null || contract_fail "the reader took an unknown kind."

# The writer and the reader together: collect_packaging_evidence.sh, run on a fixture
# bundle, writes an upstream_version the reader takes, the release's or the empty one
# of a core with no release tag, and refuses one of another shape. The bundle carries
# the release's ABIs, as the release evidence expects them.
# shellcheck source=android/r47-defaults.properties
. "$PROJECT_ROOT/android/r47-defaults.properties"
IFS=, read -r -a release_abis <<<"$R47_DEFAULT_ANDROID_ABI_FILTERS"
mkdir -p "$fixture/tree/base/assets"
for asset in COPYING LICENSE.txt SOURCE; do
    echo "$asset" >"$fixture/tree/base/assets/$asset"
done
cp "$SCRIPT_DIR/../r47_contracts/data/spdx_inventory_fixture.json" \
    "$fixture/tree/base/assets/THIRD-PARTY.spdx.json"
# Uncompressed native libraries at PAGE_ALIGNMENT_16K, as every real bundle carries.
printf '\x12\x06\x12\x04\x08\x01\x10\x02' >"$fixture/tree/BundleConfig.pb"
printf 'int r47_entry(void) { return 0; }\n' >"$fixture/lib.c"
cc -shared -fPIC -o "$fixture/lib.so" "$fixture/lib.c"
for abi in "${release_abis[@]}"; do
    mkdir -p "$fixture/tree/base/lib/$abi"
    cp "$fixture/lib.so" "$fixture/tree/base/lib/$abi/libr47zen.so"
done
(cd "$fixture/tree" && zip -qr "$fixture/fixture.aab" .)
collect() {
    rm -rf "$fixture/out"
    bash "$PROJECT_ROOT/scripts/android/collect_packaging_evidence.sh" --variant release \
        --bundle "$fixture/fixture.aab" --output-dir "$fixture/out" \
        --expected-abis "$R47_DEFAULT_ANDROID_ABI_FILTERS" --upstream-version "$1" \
        >"$fixture/collect.log" 2>&1
}
for written in 00.109.05.00b2 ""; do
    collect "$written" || contract_fail "the evidence refused upstream version '$written':" \
        "$(cat "$fixture/collect.log")"
    read_back="$(bash "$READER" "$fixture/out/BUILD-METADATA.txt" upstream_version text)" ||
        contract_fail "the reader refused the upstream version '$written' the evidence wrote."
    [ "$read_back" = "$written" ] ||
        contract_fail "the evidence wrote upstream version '$written' and the reader read '$read_back'."
done
if collect $'00.109\nversion_code=1'; then
    contract_fail "the evidence wrote an upstream version that adds a key."
fi

bare="$(workflow_grep "sed -n 's/\^[a-z_0-9]*=//p' [^ |]*BUILD-METADATA\.txt" || true)"
[ -z "$bare" ] || contract_fail "workflows read BUILD-METADATA.txt without read_build_metadata.sh:" "$bare"

contract_pass "BUILD-METADATA.txt reaches a privileged job only through read_build_metadata.sh, which refuses a value of the wrong shape."
