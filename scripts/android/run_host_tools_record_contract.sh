#!/bin/bash
# shellcheck disable=SC2016 # the fakes' bodies and the matched source text stay unexpanded
# Contract: every artifact's evidence records the host tools that generated its
# code.
#
# describe_host_tools.sh is run against fake tools on a PATH that holds nothing
# else: it prints "meson <v> ninja <v> <gcc|clang> <v> <libfreetype6|freetype2>
# <v>", keeps each version to the evidence's text characters, names clang when cc
# is clang, falls back to pkg-config's freetype2 without dpkg, and prints
# "absent" for a missing tool. Then the wiring: both build jobs record it after
# the build, both signing paths hand it to collect_packaging_evidence.sh, which
# writes host_tools and refuses a record of another shape. Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"
DESCRIBE="$SCRIPT_DIR/describe_host_tools.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/core"
for tool in bash tr sed head grep; do
    ln -s "$(command -v "$tool")" "$work/core/$tool"
done

# fake <dir> <name> <body>
fake() {
    mkdir -p "$1"
    printf '#!/bin/bash\n%s\n' "$3" >"$1/$2"
    chmod +x "$1/$2"
}
gcc_bin="$work/gcc"
fake "$gcc_bin" meson 'echo 1.7.0'
fake "$gcc_bin" ninja 'echo 1.12.1'
fake "$gcc_bin" cc 'if [ "$1" = --version ]; then echo "cc (Ubuntu 15.2.0-4ubuntu4) 15.2.0"; else echo 15.2.0; fi'
fake "$gcc_bin" dpkg-query 'echo "1:2.13.3+dfsg-1~exp1"'

describe() { PATH="$1:$work/core" bash "$DESCRIBE"; }

expected='meson 1.7.0 ninja 1.12.1 gcc 15.2.0 libfreetype6 1-2.13.3+dfsg-1-exp1'
got="$(describe "$gcc_bin")"
[ "$got" = "$expected" ] || contract_fail "describe_host_tools.sh printed the wrong record:" "got:      $got" "expected: $expected"
[[ "$got" =~ ^[A-Za-z0-9\ ._()+-]{1,200}$ ]] || contract_fail "the record is not the evidence's text kind: $got"

clang_bin="$work/clang"
fake "$clang_bin" ninja 'echo 1.12.1'
fake "$clang_bin" cc 'if [ "$1" = --version ]; then echo "Ubuntu clang version 21.1.8"; else echo 21.1.8; fi'
fake "$clang_bin" pkg-config 'echo 26.1.20'
expected='meson absent ninja 1.12.1 clang 21.1.8 freetype2 26.1.20'
got="$(describe "$clang_bin")"
[ "$got" = "$expected" ] || contract_fail "describe_host_tools.sh missed clang, pkg-config, or an absent tool:" "got:      $got" "expected: $expected"

evidence="$(strip_hash_comments <"$SCRIPT_DIR/collect_packaging_evidence.sh")"
grep -qF 'write_metadata_line "host_tools" "$host_tools"' <<<"$evidence" ||
    contract_fail "collect_packaging_evidence.sh does not write host_tools"
grep -qF '"$host_tools" =~ ^[A-Za-z0-9\ ._()+-]{1,200}$' <<<"$evidence" ||
    contract_fail "collect_packaging_evidence.sh does not refuse a record of another shape"

release_live="$(strip_hash_comments <"$WORKFLOW_DIR/android-release.yml")"
ci_live="$(strip_hash_comments <"$WORKFLOW_DIR/android-ci.yml")"
release_wrapper="$(strip_hash_comments <"$SCRIPT_DIR/collect_release_evidence.sh")"
for pair in "android-release.yml:$release_live" "android-ci.yml:$ci_live"; do
    name="${pair%%:*}"
    text="${pair#*:}"
    grep -qF 'host_tools="$(bash ./scripts/android/describe_host_tools.sh)"' <<<"$text" ||
        contract_fail "$name's build job does not record the host tools"
done
[ "$(grep -cF 'HOST_TOOLS: ${{ needs.build-production-release-bundle.outputs.host_tools }}' <<<"$release_live")" -eq 2 ] ||
    contract_fail "the two release signing jobs do not both take the build job's host tools"
grep -qF -- '--host-tools "${HOST_TOOLS:-}"' <<<"$release_wrapper" ||
    contract_fail "collect_release_evidence.sh does not pass the host tools to the evidence"
grep -qF -- '--host-tools "$HOST_TOOLS"' <<<"$ci_live" ||
    contract_fail "android-ci's signing job does not pass the host tools to the evidence"

contract_pass "every artifact's evidence records the host tools that generated its code."
