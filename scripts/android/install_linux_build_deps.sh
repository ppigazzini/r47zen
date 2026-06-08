#!/bin/bash

# Single source for the Linux build dependencies every CI job needs to build the
# host simulator and the staged Android native core. Each protected workflow job
# called these out inline with its own apt list, which drifted (only the
# simulator-sanity job installed wget, although the LLVM-runtime step in the
# build/release jobs uses it). Centralizing the core list here keeps the jobs in
# sync and makes the dependency explicit instead of relying on the runner image.
# wget is kept in the core list so every job that may reach the apt.llvm.org key
# fetch has it.
#
# Any extra, job-specific packages are passed as arguments (e.g. ccache and xvfb
# for the linux-ci build job). Pure apt install, run from a CI job after
# checkout. Not a contract test.

set -Eeuo pipefail

sudo apt-get update
sudo apt-get install --yes --no-install-recommends \
    build-essential \
    cmake \
    libexpat1-dev \
    libgmp-dev \
    libgtk-3-dev \
    libminizip-dev \
    libpulse-dev \
    meson \
    ninja-build \
    pkg-config \
    unzip \
    wget \
    zip \
    "$@"
