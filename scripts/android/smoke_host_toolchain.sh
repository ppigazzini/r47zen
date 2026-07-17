#!/bin/bash

# Smoke test for the host build-toolchain installers. Runs the REAL apt installs
# (install_linux_build_deps.sh + install_host_llvm_toolchain.sh) and asserts the
# tools they must provide actually resolve and run. This is the class of failure
# the static wiring contracts cannot catch -- a missing package, a broken repo,
# an unavailable LLVM major.
#
# Needs a Debian/Ubuntu host with sudo and network; it actually installs
# packages, so it is meant for a throwaway CI runner, not a developer machine.
#
# Usage: smoke_host_toolchain.sh [<llvm_major>]
# With no argument, tests the newest toolchain apt.llvm.org publishes for this
# Ubuntu codename. (The build lanes pin the major to the NDK Clang; here we only
# need a real, available major to exercise the install logic end to end.)

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# --- Build dependencies --------------------------------------------------------
echo "::group::install build dependencies"
bash "$SCRIPT_DIR/install_linux_build_deps.sh"
echo "::endgroup::"
for tool in cmake meson ninja pkg-config; do
    command -v "$tool" >/dev/null || fail "$tool not on PATH after install_linux_build_deps.sh"
done
echo "OK: build dependencies resolve (cmake, meson, ninja, pkg-config)."

# --- Resolve an LLVM major to test --------------------------------------------
llvm_major="${1:-}"
if [ -z "$llvm_major" ]; then
    codename="$(lsb_release -cs)"
    llvm_major="$(
        curl -fsSL "https://apt.llvm.org/${codename}/dists/" 2>/dev/null |
            grep -oE "llvm-toolchain-${codename}-[0-9]+" |
            grep -oE '[0-9]+$' | sort -n | tail -1
    )"
    [ -n "$llvm_major" ] || fail "could not determine an available LLVM major for ${codename}"
    echo "Testing the newest LLVM major apt.llvm.org publishes for ${codename}: ${llvm_major}"
fi

# --- LLVM toolchain ------------------------------------------------------------
echo "::group::install LLVM toolchain ${llvm_major}"
bash "$SCRIPT_DIR/install_host_llvm_toolchain.sh" "$llvm_major"
echo "::endgroup::"

# The binaries the PGO lanes use must resolve and run.
for tool in "llvm-config-${llvm_major}" "llvm-profdata-${llvm_major}" "clang-${llvm_major}" "lld-${llvm_major}"; do
    command -v "$tool" >/dev/null || fail "$tool not on PATH after install_host_llvm_toolchain.sh"
done
"llvm-config-${llvm_major}" --version >/dev/null || fail "llvm-config-${llvm_major} --version failed"
"clang-${llvm_major}" --version >/dev/null || fail "clang-${llvm_major} --version failed"

# The host x86_64 profiling runtime archive is the reason libclang-rt-<major>-dev
# is installed (it links the -fprofile-generate host build); assert the package
# actually ships it.
if ! dpkg -L "libclang-rt-${llvm_major}-dev" | grep -q 'libclang_rt.profile-x86_64.a'; then
    fail "libclang-rt-${llvm_major}-dev does not provide the host libclang_rt.profile-x86_64.a runtime"
fi

echo "OK: host toolchain smoke passed for LLVM ${llvm_major} (llvm-config, llvm-profdata, clang, lld, and the host profile runtime all resolve)."
