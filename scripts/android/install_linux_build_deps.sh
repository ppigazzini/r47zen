#!/bin/bash

# Single source for the Linux build dependencies every CI job needs to build the
# host simulator and the staged Android native core. Centralizing the core list
# here keeps the jobs in
# sync and makes the dependency explicit instead of relying on the runner image.
# wget is kept in the core list so every job that may reach the apt.llvm.org key
# fetch has it.
#
# Any extra, job-specific packages are passed as arguments (e.g. ccache and xvfb
# for the linux-ci build job). Pure apt install, run from a CI job after
# checkout. Not a contract test.
#
# apt drops a connection that sends nothing for 30 s and retries it three
# times. The download alone runs in 5-minute tries: each try opens fresh
# connections, and apt keeps every package an earlier try fetched. A try that
# adds bytes to apt's cache is a slow mirror and gets another; a try that adds
# none is a stall and ends the script. The calling job's timeout-minutes bounds
# the tries. The install itself runs unlimited, since a limit there could stop
# dpkg halfway.

set -Eeuo pipefail

packages=(
    build-essential
    cmake
    libexpat1-dev
    libgmp-dev
    libgtk-3-dev
    libminizip-dev
    libpulse-dev
    meson
    ninja-build
    pkg-config
    unzip
    wget
    zip
    "$@"
)
apt_options=(
    -o Acquire::http::Timeout=30
    -o Acquire::https::Timeout=30
    -o Acquire::Retries=3
)

# Print the bytes in apt's package cache, partial downloads included: a package
# larger than one try can fetch shows its progress only there, and partial/ is
# readable by root alone.
apt_cache_bytes() {
    sudo du -sb /var/cache/apt/archives | cut -f1
}

sudo apt-get "${apt_options[@]}" update
while :; do
    before=$(apt_cache_bytes)
    status=0
    timeout 300 sudo apt-get "${apt_options[@]}" install --download-only --yes \
        --no-install-recommends "${packages[@]}" || status=$?
    if [ "$status" -eq 0 ]; then
        break
    fi
    if [ "$status" -eq 124 ]; then
        ended="ran out of its 5 minutes"
    else
        ended="failed with status $status"
    fi
    fetched=$(($(apt_cache_bytes) - before))
    if [ "$fetched" -le 0 ]; then
        echo "ERROR: apt's download try $ended and fetched nothing." >&2
        exit 1
    fi
    echo "apt's download try $ended after fetching $((fetched / 1024)) KiB; trying again." >&2
done
sudo apt-get "${apt_options[@]}" install --yes --no-install-recommends "${packages[@]}"
