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

- `./scripts/upstream-sync/upstream.sh sync --auto --write-lock` hydrates the
  authoritative upstream core.
- `./scripts/android/build_android.sh` is the canonical Android debug-build path.
- `cd android && ./gradlew ...` is the module-local maintenance lane only when
  staged native inputs are already current.

Repo-owned automation layout:

- `scripts/` owns repo-only automation.
- `scripts/upstream-sync/` owns grouped upstream resolve and sync
  implementation.
- `scripts/android/` owns grouped Android build, staging, packaging, and
  helper implementations.
- `scripts/keypad-fixtures/`, `scripts/package-notices/`, and
  `scripts/workload-regressions/` own the fixture export, notice generation,
  and host workload lanes.

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

The CI workflow keeps the lane split explicit: one lane sanity-checks the
authoritative upstream simulator core with the root host suite, one lane builds,
tests, and packages Android through
`./scripts/android/build_android.sh --run-sim-tests`, Android JVM plus
emulator-backed instrumentation tests run in their own lane, and the
main-branch snapshot prerelease publishes only after those jobs pass.

Use `./scripts/android/build_android.sh --doctor` to inspect host and staging
readiness, and `./scripts/android/build_android.sh --android-only` for the fast
module-local lane when staged native inputs are current. Staging helpers stay
internal unless the task is specifically about sync or staging internals.

Repo-owned implementation scripts now live under grouped folders below
`scripts/`, primarily `scripts/android/`, `scripts/upstream-sync/`,
`scripts/keypad-fixtures/`, `scripts/package-notices/`, and
`scripts/workload-regressions/`.

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
