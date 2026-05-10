# R47 Android
[![Android CI](https://github.com/ppigazzini/r47_android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ppigazzini/r47_android/actions/workflows/android-ci.yml)

R47 Android is the Android shell, build pipeline, and maintainer overlay for
the R47 calculator variant.

The authoritative upstream project is the C47 calculator core at
`https://gitlab.com/rpncalculators/c43.git`. The GitLab path still uses the
historical `c43` repository name, but the upstream project identifies itself as
C47.

This repo interfaces with that upstream project in two layers:

- source sync and generation, which hydrate upstream-shaped root inputs such as
	`src/`, `dep/`, `meson.build`, and `res/fonts` when the active lane needs
	them
- Android-native staging and runtime integration, which stage shared-native
	inputs into `android/.staged-native/cpp` while keeping Android-owned Kotlin,
	JNI, packaging, and CI code in repo-owned overlay paths

This repo owns the Android app under `android/`, the Android bridge under
`android/app/src/main/cpp/c47-android`, repo-only build and sync automation
under `scripts/`, and maintainer docs under `android/docs/dev/`.

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

Key maintainer pages:

- `android/docs/dev/00-project-and-upstream.md`: what this repo is, what the
	upstream C47 project is, and where the ownership and interface boundary sits.
- `android/docs/dev/10-build-and-source-layout.md`: how the Android overlay
	builds, compiles, stages, and consumes the shared core.
- `android/docs/dev/20-kotlin-shell-architecture.md`: Kotlin coordinator,
	lifecycle, storage, and input flow.
- `android/docs/dev/30-native-core-and-jni.md`: CMake, JNI, HAL, and bridge
	ownership.
- `android/docs/dev/40-ui-rendering-and-gtk-mapping.md`: geometry, LCD
	projection, keypad layout, and rendering rules.
- `android/docs/dev/50-upstream-interface-surfaces.md`: the detailed Android
	interface to upstream-owned runtime behavior.
- `android/docs/dev/60-runtime-hot-paths.md`: the main runtime loops, redraw
	paths, and regression-sensitive boundaries.
- `android/docs/dev/70-ci-and-release-workflow.md`: GitHub Actions lane split,
	artifacts, and publication flow.
- `android/docs/dev/80-tests-and-contracts.md`: the maintainer map of test
	surfaces, contract owners, and rerun lanes.
