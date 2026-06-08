#!/bin/bash

# Single source for the host LLVM toolchain install used by the PGO lanes. Adds
# the apt.llvm.org repository directly and installs the toolchain matching the
# NDK Clang major, instead of piping the upstream llvm.sh installer into a root
# shell -- these are the Ubuntu steps llvm.sh performs (CI runners are always
# Ubuntu), inlined so CI runs only reviewed commands and does not break when the
# upstream script is rewritten.
#
# llvm-<major> is installed alongside clang/lld/libclang-rt because it ships
# llvm-config-<major> (used below to resolve the bindir) and llvm-profdata
# -<major> (the host PGO profile merger); --no-install-recommends drops both
# otherwise. That omission once shipped to CI and failed at runtime with
# "llvm-config-<major>: command not found".
#
# Usage: install_host_llvm_toolchain.sh <llvm_major>
# Prints the toolchain bindir; when GITHUB_PATH is set, also appends it so later
# workflow steps find the versioned tools.
#
# Source: https://apt.llvm.org/llvm.sh
#         https://github.com/opencollab/llvm-jenkins.debian.net/blob/master/llvm.sh

set -Eeuo pipefail

llvm_major="${1:?usage: install_host_llvm_toolchain.sh <llvm_major>}"

codename="$(lsb_release -cs)"
wget -qO- https://apt.llvm.org/llvm-snapshot.gpg.key |
    sudo tee /etc/apt/trusted.gpg.d/apt.llvm.org.asc >/dev/null
echo "deb http://apt.llvm.org/${codename}/ llvm-toolchain-${codename}-${llvm_major} main" |
    sudo tee "/etc/apt/sources.list.d/apt-llvm-org-${llvm_major}.list" >/dev/null
sudo apt-get update
sudo apt-get install --yes --no-install-recommends \
    "llvm-${llvm_major}" \
    "clang-${llvm_major}" \
    "clang-tools-${llvm_major}" \
    "lld-${llvm_major}" \
    "libclang-rt-${llvm_major}-dev"

llvm_bindir="$(llvm-config-"$llvm_major" --bindir)"
if [ -n "${GITHUB_PATH:-}" ]; then
    echo "$llvm_bindir" >>"$GITHUB_PATH"
fi
echo "$llvm_bindir"
