#!/bin/bash

# Contract: any file that locates the host LLVM toolchain with
# `llvm-config-<major>` must also install the `llvm-<major>` apt package. That
# package ships /usr/bin/llvm-config-<major> AND /usr/bin/llvm-profdata-<major>
# (the host PGO profile merger); the minimal clang/clang-tools/lld/libclang-rt
# set does not. Dropping llvm-<major> makes the install die with
# `llvm-config-<major>: command not found` (exit 127) -- but only at CI runtime,
# which is what this guards against statically. The install lives in
# install_host_llvm_toolchain.sh, so that script is scanned alongside the
# workflows. Pure host test, no apt needed.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/ci_contract.sh
source "$SCRIPT_DIR/../lib/ci_contract.sh"

status=0
for f in "$WORKFLOW_DIR"/*.yml "$PROJECT_ROOT/scripts/android/install_host_llvm_toolchain.sh"; do
    # Only files that actually invoke llvm-config-<major> (strip_yaml_comments
    # ignores comment lines that merely mention it, e.g. a contract step's own
    # description).
    strip_yaml_comments <"$f" | grep -qF 'llvm-config-' || continue
    if ! grep -qF '"llvm-${llvm_major}"' "$f"; then
        echo "FAIL: $(basename "$f") uses llvm-config-<major> but never installs the llvm-\${llvm_major} package that provides it (and llvm-profdata)." >&2
        status=1
    fi
done

if [ "$status" -ne 0 ]; then
    exit 1
fi

contract_pass "every file using llvm-config-<major> installs the llvm-<major> package."
