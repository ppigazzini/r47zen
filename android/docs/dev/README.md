# Android Development Docs

This directory is the active Android maintainer documentation surface for the
checked-in R47 shell.

They are code-facing development notes, not end-user usage docs.

Keep the maintainer doc split simple:

- this page is the maintainer index
- `10-build-and-source-layout.md` is the canonical Android ownership, build,
  and rebuild contract
- the numbered topic pages carry the deeper subsystem details

Public maintainer entrypoints:

- `./scripts/sync_public.sh` hydrates the authoritative upstream core.
- `./scripts/build_android.sh` is the canonical Android debug-build path.
- `cd android && ./gradlew ...` is the module-local maintenance lane only when
  staged native inputs are already current.

Repo-owned automation layout:

- `scripts/` owns repo-only automation.
- `scripts/upstream.sh` owns upstream resolve and sync implementation.
- `scripts/android/` owns Android staging, packaging, and build-helper
  implementations.
- `scripts/build_android.sh` and `scripts/sync_public.sh` are the maintainer
  entrypoints.

Read in this order:

- [10-build-and-source-layout.md](10-build-and-source-layout.md): toolchain,
  source ownership, build entry points, rebuild contract, and CI.
- [20-kotlin-shell-architecture.md](20-kotlin-shell-architecture.md):
  lifecycle, runtime ownership, input flow, storage, and Kotlin-side structure.
- [30-native-core-and-jni.md](30-native-core-and-jni.md): CMake, JNI,
  synchronization, HAL I/O, and packaging constraints.
- [40-ui-rendering-and-gtk-mapping.md](40-ui-rendering-and-gtk-mapping.md):
  shell projection, keypad scene data, and GTK-derived rendering rules.
- [90-official-references.md](90-official-references.md): official Android,
  NDK, Kotlin, storage, and view-system references.

The CI lane follows the same ownership model as the local build: resolve one
authoritative upstream core revision, run the root simulator tests, build the
debug APK, run Android JVM plus emulator-backed instrumentation tests, then
publish a main-branch snapshot prerelease only after those jobs pass.

Use `./scripts/build_android.sh --doctor` to inspect host and staging readiness, and
`./scripts/build_android.sh --android-only` for the fast module-local lane when staged
native inputs are current. Staging helpers stay internal unless the task is
specifically about sync or staging internals.

Repo-owned implementation scripts now live under `scripts/` and
`scripts/android/`.

Shared Android SDK, NDK, CMake, build-tools, hosted-emulator, and xlsxio pins
live in `android/r47-defaults.properties`.

Two rules govern most Android work in this repository:

1. The preferred source of truth for shared calculator behavior is the synced
   root tree plus generated outputs from `build.sim`.
2. The build-only staged Android native input tree lives under
  `android/.staged-native/cpp`. The former tracked
  `android/app/src/main/cpp/{c47,generated,decNumberICU,gmp}` tree has been
  retired and must stay absent, while `android/app/src/main/cpp/c47-android`
  stays the Android-owned bridge, HAL, and stub surface. Public checkouts keep
  only one explicit staging-only mini-gmp fallback under
  `android/compat/mini-gmp-fallback`.

If a change crosses both Kotlin and native boundaries, read the build page and
the JNI page before editing.
