# R47 Zen
[![Android CI](https://github.com/ppigazzini/r47zen/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ppigazzini/r47zen/actions/workflows/android-ci.yml)

R47 Zen packages the upstream C47 calculator core as an Android app for the
R47 calculator variant.

This repository builds the APK, stages the shared native calculator core for
Android, and owns the Android-specific Kotlin, JNI, packaging, and CI code that
run the calculator on Android devices.

The Android-native bridge also carries the repo-owned `PC_BUILD`
compatibility layer required by the staged upstream core. When upstream syncs
introduce new HAL helper exports used by the desktop-shaped core, the Android
HAL under `android/app/src/main/cpp/r47zen/hal/` must be updated in the same
iteration so the latest-upstream Android CI link stays green.

The authoritative upstream source repository is
`https://gitlab.com/rpncalculators/c43.git`. The GitLab path still uses the
historical `c43` repository name, but the upstream project identifies itself as
C47.

Android CI pins staged native inputs and staged `PROGRAMS` fixtures to that
same workflow-selected upstream commit so the grouped release-path fixture lane
never mixes different core revisions in one run.

## Repo Layout

- `android/`: Android app, Gradle project, packaging, and Android-owned native
  glue
- `android/app/src/main/cpp/r47zen/`: Android bridge, JNI, HAL, and other
  Android-local native code
- `scripts/`: repo-owned Android build, staging, and upstream sync automation
- `android/docs/dev/`: maintainer docs for source ownership, build flow, JNI,
  rendering, CI, and verification

## Quick Start

Prerequisites: JDK 17, the Android SDK and NDK, CMake, Meson/Ninja, and `uv`
for the Python contract lane. Rather than list exact versions here (they live in
`android/r47-defaults.properties`), probe your machine and let the build report
what is missing:

```sh
./scripts/android/build_android.sh --doctor
```

Hydrate the upstream core inputs:

```sh
./scripts/upstream-sync/upstream.sh sync --auto --write-lock
```

Build the debug APK:

```sh
./scripts/android/build_android.sh
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
The root `Makefile` and `BUILD.md` are upstream-owned and describe the upstream
simulator build, not the Android lane; see `android/docs/dev/` for this repo's
build. Full local pipeline: `android/docs/dev/10-build-and-source-layout.md`.

Signed prerelease and production release publication run from the CI and
release workflows. Nightly `-dev` snapshot pre-releases are pruned on a
schedule (short TTL, newest few retained) while signed releases are kept. See
`android/docs/dev/70-ci-and-release-workflow.md` for the lane split,
signing-key policy, retention, and published artifacts.

## Documentation

Use `android/docs/dev/README.md` as the maintainer index.

Project-facing publication docs:

- `android/docs/privacy-policy.md`: privacy policy for the independently maintained
  Android build and the current Play Console policy target.

Key maintainer pages:

- `android/docs/dev/00-project-and-upstream.md`: what this repo is, what the
  upstream C47 project is, and where the ownership and interface boundary sits.
- `android/docs/dev/10-build-and-source-layout.md`: how the Android overlay
  builds, compiles, stages, and consumes the shared core.
- `android/docs/dev/20-kotlin-shell-architecture.md`: Kotlin coordinator,
  lifecycle, storage, and input flow.
- `android/docs/dev/30-upstream-interface-surfaces.md`: the detailed Android
  interface to upstream-owned runtime behavior.
- `android/docs/dev/40-native-core-and-jni.md`: CMake, JNI, HAL, and bridge
  ownership.
- `android/docs/dev/50-ui-rendering-and-gtk-mapping.md`: geometry, LCD
  projection, keypad layout, and rendering rules.
- `android/docs/dev/60-runtime-hot-paths.md`: the main runtime loops, redraw
  paths, and regression-sensitive boundaries.
- `android/docs/dev/70-ci-and-release-workflow.md`: GitHub Actions lane split,
  artifacts, and publication flow.
- `android/docs/dev/80-tests-and-contracts.md`: the maintainer map of test
  surfaces, contract owners, and rerun lanes.
- `android/docs/dev/90-official-references.md`: official Android, NDK, Gradle,
  Kotlin, GitHub Actions, and upstream reference surfaces.
