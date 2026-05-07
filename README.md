# R47 Android
[![Android CI](https://github.com/ppigazzini/r47_android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ppigazzini/r47_android/actions/workflows/android-ci.yml)

R47 Android packages the R47 calculator for Android on top of the authoritative
upstream C47 core from https://gitlab.com/rpncalculators/c43.git.

This repository contains:

- the Android app under `android/`
- the Android-owned native bridge under `android/app/src/main/cpp/c47-android`
- build and sync automation under `scripts/`
- maintainer documentation under `android/docs/dev/`

## Quick Start

Hydrate the upstream core:

```sh
./scripts/upstream-sync/upstream.sh sync --auto --write-lock
```

Build the debug APK:

```sh
./scripts/android/build_android.sh
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Documentation

Use `android/docs/dev/README.md` as the maintainer index.
Use `android/docs/dev/10-build-and-source-layout.md` for the canonical build,
CI lane, and ownership contract.
