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
# otherwise.
#
# Usage: install_host_llvm_toolchain.sh <llvm_major>
# Prints the toolchain bindir; when GITHUB_PATH is set, also appends it so later
# workflow steps find the versioned tools.
#
# Source: https://apt.llvm.org/llvm.sh
#         https://github.com/opencollab/llvm-jenkins.debian.net/blob/master/llvm.sh

set -Eeuo pipefail

llvm_major="${1:?usage: install_host_llvm_toolchain.sh <llvm_major>}"
if [[ ! "$llvm_major" =~ ^[0-9]+$ ]]; then
    echo "llvm_major must be a positive integer. Got: ${llvm_major}" >&2
    exit 1
fi

codename="$(lsb_release -cs)"
# Scope the apt.llvm.org key with signed-by to just its own repository, rather
# than dropping it in trusted.gpg.d where it would authenticate every repo on
# the system.
keyring="/etc/apt/keyrings/apt.llvm.org.asc"
sudo install -d -m 0755 /etc/apt/keyrings
wget -qO- https://apt.llvm.org/llvm-snapshot.gpg.key |
    sudo tee "$keyring" >/dev/null
echo "deb [signed-by=${keyring}] http://apt.llvm.org/${codename}/ llvm-toolchain-${codename}-${llvm_major} main" |
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
