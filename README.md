# R47 Android

R47 Android is the Android shell, native staging pipeline, and maintainer
overlay for the R47 calculator variant built from the authoritative upstream
C47 source repository https://gitlab.com/rpncalculators/c43.git

## Source And Ownership

- `upstream.source` defines the upstream C47 repository consumed by this repo.
- `./scripts/sync_public.sh` hydrates the upstream core and the upstream root
  build inputs required by the simulator and Android staging flow.
- `android/` owns the Android app, compliance assets, compatibility inputs, and
  maintainer documentation.
- `scripts/` owns repo automation, including upstream sync, Android staging,
  packaging evidence, and build entrypoints.
- `android/app/src/main/cpp/c47-android` owns the Android-native bridge, HAL,
  and stubs.
- `android/.staged-native/cpp` is the build-only staged native input root used
  by the Android CMake build.
- `android/compat/mini-gmp-fallback` provides the tracked mini-gmp staging
  source used by Android builds.

## Build Entry Points

- `./scripts/build_android.sh` is the canonical Android debug build.
- `./scripts/build_android.sh --doctor` reports SDK, NDK, CMake, xlsxio,
  upstream, and staged-input readiness.
- `./scripts/build_android.sh --android-only` rebuilds the Android module when
  the staged native inputs are already current.
- `cd android && ./gradlew assembleDebug` is the module-local maintenance command
  when the staged native inputs are already current.
- `make test` validates the hydrated upstream simulator and generator lane.

`scripts/build_android.sh` prefers the repo-local `android/gradlew` launcher
backed by the retained wrapper runtime under `android/gradle/wrapper/` and
falls back to a host `gradle` command only when that repo wrapper path is not
available.

## Outputs

- The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
- Packaging evidence is collected by
  `scripts/android/collect_packaging_evidence.sh`.
- Maintainer docs live under `android/docs/dev/`.

Start with `android/docs/dev/README.md` for maintainer documentation and use
`android/docs/dev/10-build-and-source-layout.md` as the canonical build and
ownership contract.
