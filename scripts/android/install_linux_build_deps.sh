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
# A slow mirror is where the slow jobs went: one install fetched 21.3 MB at
# 17 kB/s for 20 minutes. apt gives up on a stalled connection after 30 s and
# retries three times, and the download, alone, runs under a 5-minute limit with
# one more try; the install itself runs unlimited, since a limit there could
# stop dpkg halfway.

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

sudo apt-get "${apt_options[@]}" update
for attempt in 1 2; do
    if timeout 300 sudo apt-get "${apt_options[@]}" install --download-only --yes \
        --no-install-recommends "${packages[@]}"; then
        break
    fi
    if [ "$attempt" -eq 2 ]; then
        echo "ERROR: apt could not download the build dependencies in two tries of 5 minutes." >&2
        exit 1
    fi
    echo "apt's download stalled or failed; trying once more." >&2
done
sudo apt-get "${apt_options[@]}" install --yes --no-install-recommends "${packages[@]}"
