#!/bin/bash

# Contract: read_upstream_version.sh must print VERSION1 from a defines.h and
# print nothing for a missing file or a malformed define.
#
# Pure host test.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

READER="$SCRIPT_DIR/read_upstream_version.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

defines="$work/defines.h"
printf '%s\n' '// Version 00.109.02.07b11   Public Release' \
    '#define VERSION1 "00.109.05.00b2"       // major . minor . build' >"$defines"
[ "$(bash "$READER" "$defines")" = "00.109.05.00b2" ] ||
    contract_fail "read_upstream_version.sh did not read VERSION1."
[ -z "$(bash "$READER" "$work/absent.h")" ] ||
    contract_fail "read_upstream_version.sh printed a value for a missing file."
printf '%s\n' '#define VERSION1 "00.109 beta"' >"$defines"
[ -z "$(bash "$READER" "$defines")" ] ||
    contract_fail "read_upstream_version.sh printed a malformed VERSION1."

contract_pass "read_upstream_version.sh reads VERSION1 from defines.h."
