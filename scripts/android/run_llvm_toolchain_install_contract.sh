#!/bin/bash

# Contract: any workflow that locates the host LLVM toolchain with
# `llvm-config-<major>` must also install the `llvm-<major>` apt package. That
# package ships /usr/bin/llvm-config-<major> AND /usr/bin/llvm-profdata-<major>
# (the host PGO profile merger); the minimal clang/clang-tools/lld/libclang-rt
# set does not. Dropping llvm-<major> makes the install step die with
# `llvm-config-<major>: command not found` (exit 127) -- but only at CI runtime,
# which is what this guards against statically. Pure host test, no apt needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

status=0
for wf in "$WORKFLOW_DIR"/*.yml; do
    # Only workflows that actually invoke llvm-config-<major> (strip_yaml_comments
    # ignores comment lines that merely mention it, e.g. a contract step's own
    # description).
    strip_yaml_comments <"$wf" | grep -qF 'llvm-config-' || continue
    if ! grep -qF '"llvm-${llvm_major}"' "$wf"; then
        echo "FAIL: $(basename "$wf") uses llvm-config-<major> but never installs the llvm-\${llvm_major} package that provides it (and llvm-profdata)." >&2
        status=1
    fi
done

if [ "$status" -ne 0 ]; then
    exit 1
fi

contract_pass "every workflow using llvm-config-<major> installs the llvm-<major> package."
