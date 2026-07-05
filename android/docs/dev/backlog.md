# Backlog

Durable, in-tree list of known-deferred work. The detailed analysis lives in
the git-ignored `__DEV/reports/` iteration docs, which a fresh clone does not
have; this file keeps the backlog itself visible to anyone with the repo. Move
an item to a commit when it lands, and delete it here.

## Native / build

- Curated clang-tidy CI lane (`bugprone-*`, `cert-*`, `clang-analyzer-*`) over
  the repo-owned C, advisory first. CodeQL C/C++ (build-free) already covers
  the native static-analysis gap; clang-tidy is complementary. Deferred because
  it was not installable/verifiable in the implementing environment.
- Hardened build flags vs the OpenSSF guide: `-mbranch-protection=standard`
  (arm64 BTI/PAC) first, then `-fstack-clash-protection`,
  `-ftrivial-auto-var-init=zero`, `-fstrict-flex-arrays=3`, FORTIFY=3. Measure
  size/perf on the workload corpus per flag before adopting.

## CI

- zizmor online audits (known-vulnerable-action lookups) and an actionlint
  lane, added to the `workflow-lint` job. The offline zizmor lane ships now.
- Release-history auto-commit-back: the release workflow currently emits the
  release-history row to the job summary for the maintainer to append to
  `android/docs/dev/release-history.md`. An auto-commit-back was deferred
  (needs contents:write + push-to-main + conflict handling; risky, untestable).
- Emulator lane: bump `R47_DEFAULT_ANDROID_TEST_API_LEVEL` from 34 to 35/36,
  with a forward-compat 37 lane when images land.
- Auto-derive the release version in `android-release.yml` (read the latest
  `r47zen-v*` tag, compute the next `versionName` / YYYYMMDDVV `versionCode`)
  and demote the dispatch inputs to optional overrides. Strict format
  validation of the typed inputs ships now; full derivation is deferred (it
  changes the release UX and was not testable in the implementing environment).

## Test integrity

- Replace the hand-rolled exact-hash plot goldens with tolerance-based
  screenshot testing (Roborazzi) so upstream render drift stops forcing blind
  re-pins.
- A machine-independent SPIRALk value oracle (final X-register value at
  completion) so it is not liveness-only.
- Self-referential render goldens (`ReplicaOverlayGoldenTest` CHROME_TEXT_GOLDEN,
  `ExportedKeypadFixtureRenderTest`, `KeyboardLayoutContractRenderTest`):
  replace producer-equals-own-output checks with independent literal
  expectations.

## Security / process (maintainer-owned)

- Move `__DEV/__sign` keystores and their cleartext passwords out of the
  git-ignored working directory into an encrypted store; separate passwords
  from keys. (Local hygiene; the keys are also in GitHub secrets and, under Play
  App Signing, a lost upload key is resettable.)
- Enable the Immutable Releases repository setting (Settings -> Releases).

## Kotlin / docs cosmetics (carried from REPORT-31)

- Dead Kotlin surface: `ReplicaOverlay.onSettingsTapListener`,
  `onLongPressListener`, `updateLcd`, `NativeCoreRuntime.requestForceRefresh`,
  `StorageAccessCoordinator.requestWorkDirectory` (some are test-only; sweep
  carefully).
- Hardcoded clip-label / toast strings; `AudioEngine` >1.08 s tone truncation.
- Magic `52` vs `LCD_ROW_SIZE_BYTES`; recursive-mutex depth tracking in
  `jni_storage.c` (works on the pinned NDK pthread).
- `90-official-references.md` external version cruft.
