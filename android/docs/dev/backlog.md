# Backlog

Durable, in-tree list of known-deferred work, reconciled with the retired-items
ledger from the audit iterations. The detailed analysis lives in the git-ignored
`__DEV/reports/` iteration docs, which a fresh clone does not have; this file
keeps the backlog itself visible to anyone with the repo. Move an item to a
commit when it lands, and delete it here. An item under "Retired on evidence"
stays closed unless new evidence reopens it.

## Open (deferred, with risk notes)

- Hardened build flags beyond the shipped set. Already shipped:
  `-mbranch-protection=standard` (arm64 BTI/PAC) and `-fstack-clash-protection`.
  Still deferred, in priority order:
  - `FORTIFY_SOURCE=3` over the NDK default `2`: low marginal value and the
    `2`-vs-`3` upgrade is not observable in the linked binary (both emit
    `__*_chk` calls), so it is a poor fit for a real test. Verify on a release
    build if pursued.
  - `-ftrivial-auto-var-init=zero`: measurable cost on the decNumber/plot hot
    loops; profile on the workload corpus before adopting.
  - `-fstrict-flex-arrays=3`: **correctness risk, likely reject.** It can
    miscompile the decNumber `lsu[]` and mini-gmp flexible-array idioms on this
    exact core. Gate behind a full workload-regression + ASan/UBSan pass on the
    decNumber corpus, and be prepared to reject it outright.
- Roborazzi (or equivalent) tolerance-based plot goldens. The adopt trigger
  (a third BinetV3/GudrmPL display-hash re-pin) has fired, so this is now a
  decision, not a gated wait: either adopt tolerance goldens for the two plot
  fixtures or explicitly re-document the exact-hash strategy. Restore a
  post-program softmenu oracle with the corrected `MNU_SHOW` expectation so menu
  correctness is guarded independently of the whole-screen hash.
- FACTORS value oracle. `FactorsInstrumentedTest` asserts only the result type,
  not the value. Needs either an emulator capture of the reproducible
  X-register matrix string or a new host-workload function-invocation scenario
  (seed `kR47LargeFactorsInput`, call `ITM_FACTORS`, read the register string).
- Keyboard static-override contract is stale. `derive_keyboard_layout_contract`'s
  `load_android_static_label_overrides` targets a `keyCode == N && type ==`
  construct that `jni_display.c` has been refactored away from (now
  `resolveMainKeyLabelInfo`/`makeMainLabelPresentation`), so its negative
  assertions for keys 11/12/37 pass vacuously against an empty override set.
  Redesign the contract for the presentation architecture; do NOT simply
  raise-on-empty (that would fail immediately against the legitimately empty
  set).
- Sync prune and provenance (needs a full sync + re-stage + Android-build
  verification cycle): prune upstream-owned surfaces before the overlay so files
  deleted upstream do not persist (retiring the manual `rm -rf` churn
  mitigation); prune retired `generated/`/`gmp/` staged files; and verify the
  staged tree's recorded commit matches the resolved commit before stamping
  provenance.
- Vacuous-pass gate nits: `run_llvm_toolchain_install_contract.sh` scan-count
  floor; `ci_contract.sh` negative-pattern empty-scan floor;
  `run_privacy_policy_parity_contract.sh` heading floor; `coverage_gate.sh`
  counterless-class handling; wire or drop `run_16kb_runtime_smoke.sh`.
- zizmor online audits (known-vulnerable-action lookups). Deferred for its
  per-run network dependency; the offline zizmor lane and actionlint already
  ship.
- Recursive-mutex depth tracking in `jni_storage.c` (works on the pinned NDK
  pthread; documented as a portability caveat).

## Maintainer-owned

- Move `__DEV/__sign` keystores and their cleartext passwords out of the
  git-ignored working directory into an encrypted store; separate passwords from
  keys. (Local hygiene; the keys are also in GitHub secrets and, under Play App
  Signing, a lost upload key is resettable.)
- Enable the Immutable Releases repository setting (Settings -> Releases). The
  release workflow now also refuses to overwrite an existing tag unless
  `allow_overwrite` is set, so this is defense in depth.
- Emulator lane: bump `R47_DEFAULT_ANDROID_TEST_API_LEVEL` from 34 to 35 once
  the lane is green twice; add 36/37 forward-compat lanes when stable images
  land.

## Retired on evidence (do not reopen without new evidence)

- Curated clang-tidy CI lane: all findings triaged as bionic `insecureAPI`
  noise, literal `strcpy`, or defensive dead stores, over CodeQL's existing
  build-free C/C++ coverage. Pure noise.
- Release-history auto-commit-back: would grant the release job push-to-main
  rights for one manual paste. The ledger is instead backfilled in
  `release-history.md`, and the workflow still emits the per-release row to the
  job summary.
- Auto-derive the release version: strict format validation already ships, and
  an explicit validated input is safer than a tag-derived auto-computation that
  could mis-compute the next code/name.
- Self-referential render goldens: the decode path is independently literal-pinned
  by `KeypadSnapshotDecoderTest`, so the render tests legitimately cover the
  orthogonal view-update path; `CHROME_TEXT_GOLDEN` is a committed literal, not a
  producer-equals-output check.
- `AudioEngine` >1.08 s tone truncation: the maximum requested tone is 200 ms
  (`_Buzz` callers), so the truncation branch never fires.
- Dead Kotlin sweep: `onSettingsTapListener`, `onLongPressListener`, and
  `StorageAccessCoordinator.requestWorkDirectory` were deleted;
  `NativeCoreRuntime.requestForceRefresh` is kept (removing it only relocates a
  dead symbol into the JNI-registered ABI); `ReplicaOverlay.updateLcd` is a
  test-only seam, kept.
