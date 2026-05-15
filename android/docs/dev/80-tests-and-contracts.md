# Tests And Contracts

This page maps the maintainer verification surfaces, the contracts they lock,
and the smallest rerun lane that should move with each kind of change.

Read `10-build-and-source-layout.md` first. This page assumes the build,
ownership, and CI lane split are already clear.

Read this page when a task changes geometry, keypad snapshots, JNI seams,
storage flow, READP program loading, runtime cadence, or CI verification.

## Verification Flow

```mermaid
flowchart TD
    A[Contract or behavior changes]
    B{Changed surface}
    C[Python geometry and visual-policy contracts]
    D[JVM contract and parity tests]
    E[Instrumentation seams and runtime execution]
    F[Host workload regression]
    G[Full Android build and CI lanes]

    A --> B
    B -->|geometry, label, visual policy| C
    B -->|decoder, renderer, keypad, runtime, keyboard| D
    B -->|READP, SAF, graph redraw, device runtime| E
    B -->|pause, wait, progress, PC_BUILD bridge| F
    C --> G
    D --> G
    E --> G
    F --> G
```

## Contract Inventory

| Contract surface | Source of truth | Focused verification surfaces | First rerun lane |
| --- | --- | --- | --- |
| shell geometry and LCD frame | `scripts/r47_contracts/derive_shell_geometry.py`, `R47Geometry.kt` | `scripts/r47_contracts/test_shell_geometry_contract.py`, `ReplicaOverlayGoldenTest.kt` | grouped `scripts/r47_contracts` validation lane, then `:app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.ReplicaOverlayGoldenTest` |
| key-label and visual policy constants | `scripts/r47_contracts/derive_key_label_geometry.py`, `scripts/r47_contracts/derive_key_visual_policy.py`, `CalculatorKeyView.kt` | `scripts/r47_contracts/test_key_label_geometry_contract.py`, `scripts/r47_contracts/test_key_visual_policy_contract.py` | grouped `scripts/r47_contracts` validation lane |
| top-label lane solve and alpha-case label export | `scripts/r47_contracts/derive_top_label_lane_layout.py`, staged `assign.c` and `items.c`, `jni_display.c`, `ReplicaKeypadLayout.kt`, `CalculatorKeyView.kt` | `scripts/r47_contracts/test_top_label_lane_layout_contract.py`, `scripts/r47_contracts/test_alpha_case_export_contract.py`, `DynamicKeypadParityFixtureTest.kt` | grouped contract scripts first, then `:app:testDebugUnitTest` |
| overlay geometry replay for unchanged keypad scenes | `MainActivity.kt`, `ReplicaOverlayController.kt`, `ReplicaOverlay.kt`, `ReplicaKeypadLayout.kt` | `DynamicKeypadParityFixtureTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| keypad scene export manifest and decoder | `KeypadSnapshot`, exported keypad fixtures, `jni_display.c` | `KeypadFixtureContractTest.kt`, `KeypadSnapshotDecoderTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| rendered keypad and softkey semantics | `ReplicaKeypadLayout.kt`, `CalculatorKeyView.kt`, `CalculatorSoftkeyPainter.kt`, `ReplicaOverlayController.kt`, `KeypadLabelModes.kt` | `ExportedKeypadFixtureRenderTest.kt`, `CalculatorSoftkeyPainterContractTest.kt`, `CalculatorSoftkeyPainterCanvasTest.kt`, `ReplicaOverlayGoldenTest.kt`, `ReplicaOverlayControllerLabelModeTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| physical keyboard mapping | `PhysicalKeyboardMapper`, `PhysicalKeyboardInputController` | `PhysicalKeyboardInputParityTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| core thread, display loop, and runtime gate behavior | `NativeCoreRuntime.kt`, `NativeDisplayRefreshLoop.kt`, `jni_lifecycle.c`, `android_runtime.c` | `NativeCoreRuntimeTest.kt`, `GraphRedrawInstrumentedTest.kt`, `run_workload_regressions.sh` | JVM test or host workload lane depending on the owner path |
| settings lifecycle and activity recreation LCD preservation | `MainActivity.kt`, `NativeCoreRuntime.kt`, `jni_activity_bridge.c`, `jni_lifecycle.c`, `ProgramLoadTestBridge.kt` | `DisplayLifecycleInstrumentedTest.kt`, `scripts/android/run_16kb_runtime_smoke.sh` | `:app:assembleDebugAndroidTest` plus `:app:connectedDebugAndroidTest`, or `bash ./scripts/android/run_16kb_runtime_smoke.sh` when 16 KB runtime proof matters |
| settings behavior copy and settings-owned dark surfaces | `SettingsActivity.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `android/app/src/main/res/values/strings.xml`, `AndroidManifest.xml`, `android/app/src/main/res/values/themes.xml` | `SettingsActivityThemeTest.kt`, `SettingsPreferenceSummaryTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.SettingsActivityThemeTest --tests io.github.ppigazzini.r47zen.SettingsPreferenceSummaryTest` |
| haptic preference normalization and controller dispatch | `HapticFeedbackController.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `MainActivity.kt` | `HapticFeedbackControllerTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.HapticFeedbackControllerTest` |
| beeper volume normalization and audio settings dispatch | `MainActivityPreferenceController.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `MainActivity.kt` | `MainActivityPreferenceControllerTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.MainActivityPreferenceControllerTest` |
| main shell visible bars and settings-discovery hint surfaces | `MainActivity.kt`, `WindowModeController.kt`, `ReplicaOverlay.kt`, `android/app/src/main/res/values/themes.xml` | `MainShellThemeTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.MainShellThemeTest` |
| SAF picker, startup work-directory routing, detached-fd handoff, and work-directory tree persistence | `StorageAccessCoordinator.kt`, `SettingsActivity.kt`, `WorkDirectory.kt`, `jni_storage.c`, `hal/io.c` | `StorageAccessCoordinatorTest.kt`, `WorkDirectoryTest.kt`, `StorageAccessCoordinatorInstrumentedTest.kt` | JVM tests first, then `:app:assembleDebugAndroidTest` and instrumentation when the Android-only seam moved |
| program load and run through Android READP | `ProgramLoadTestBridge.kt`, `jni_program_load_test.c`, staged `PROGRAMS` fixtures | `ProgramFixtureInstrumentedTest.kt`, `FactorsInstrumentedTest.kt` | `:app:assembleDebugAndroidTest` plus `:app:connectedDebugAndroidTest` |
| pause, wait, and progress compatibility in `PC_BUILD` mode | `android_runtime.c`, staged core, workload harness | `scripts/workload-regressions/run_workload_regressions.sh`, `host_workload_regression.c` | host workload regression, then `./scripts/android/build_android.sh --run-sim-tests` |
| upstream sync restore boundary | `scripts/upstream-sync/upstream.sh` | `scripts/upstream-sync/upstream.sh verify-restore-boundary` | `bash ./scripts/upstream-sync/upstream.sh verify-restore-boundary` |

## Python Contract Suite

The canonical R47 contract suite lives under `scripts/r47_contracts/`.
It keeps two canonical checked-in source files:
`scripts/r47_contracts/data/r47_physical_geometry.json` for measured
physical-R47 geometry and
`scripts/r47_contracts/data/r47_android_ui_contract.json` for the implemented
Android logical canvas, chrome, key-surface, label layout, and solver policy.
When a historical external GIMP export needs checking, pass its JSON path directly to
`validate_geometry_dataset.py`; no checked-in GIMP dataset exists in this
repository.

The grouped Python lane currently covers:

- `validate_geometry_dataset.py`: structural and spacing checks for the
  physical dataset plus Android UI contract validation against `R47Geometry.kt`,
  `R47KeypadPolicy.kt`, and `CalculatorKeyView.kt`; this now includes the
  native-only `chrome.lcd_windows.native` contract, the native `400 x 240`
  aspect-ratio lock, integer width and height for native mode, and the rule
  that `chrome.lcd_windows` exposes only the native LCD window
- `derive_touch_grid.py`: shared touch-grid payload derivation from measured key
  centers
- `test_shell_geometry_contract.py`: logical canvas, the native LCD rectangle,
  the native LCD aspect-ratio and centered integer-bounds contract, and shell
  constants against `R47Geometry.kt`
- `test_key_label_geometry_contract.py`: key-label and key-surface constants,
  plus primary, top-label, and fourth-label anchor formulas, against
  `CalculatorKeyView.kt`, `R47KeypadPolicy.kt`, and `R47Geometry.kt`
- `test_top_label_lane_layout_contract.py`: spacing, corridor, screen-edge, and
  scale rules for the top-label solver
- `test_key_visual_policy_contract.py`: visual-policy constants against
  `R47KeypadPolicy.kt`
- `test_alpha_case_export_contract.py`: staged core alpha-label export rules in
  `assign.c`, `items.c`, and `jni_display.c`, plus the Kotlin alpha-layout
  handling in `CalculatorKeyView.kt` and `ReplicaKeypadLayout.kt`

These Python tests are the first contract surface to inspect when a geometry or
label rule change begins in the checked-in calculators-specific payloads rather
than in Android runtime glue.

## Android JVM Contract Suite

The focused JVM suite under `android/app/src/test/java/io/github/ppigazzini/r47zen/` is the
main parity surface for Kotlin-side decoder, renderer, lifecycle, and input
contracts.

Important contract files include:

- `KeypadFixtureContractTest.kt`: asserts that the exported keypad-fixture
  manifest still matches `KeypadSnapshot` contract values such as scene version,
  metadata length, key count, and labels-per-key
- `KeypadSnapshotDecoderTest.kt`: asserts the fixed metadata-lane decode and
  label fallback behavior of `KeypadSnapshot.fromNative(...)`
- `DynamicKeypadParityFixtureTest.kt`: locks unchanged-snapshot skip behavior,
  alpha-layout behavior, layout-class-sensitive keypad rendering, and
  controller-owned same-snapshot replay after PiP exit
- `ExportedKeypadFixtureRenderTest.kt`: proves exported keypad fixtures apply to
  both main keys and softkeys in the live renderer path
- `CalculatorSoftkeyPainterContractTest.kt` and
  `CalculatorSoftkeyPainterCanvasTest.kt`: lock softkey content-description,
  overlay, preview, and strike rendering rules
- `ReplicaOverlayGoldenTest.kt`: keeps the native shell rendering stable
  through golden hashes and locks the retained top settings-strip interaction
- `MainShellThemeTest.kt`: locks the `WindowModeController` PiP request to the
  native LCD `400 x 240` aspect ratio and keeps the visible-system-bar theme
  contract covered in the same focused JVM lane
- `ReplicaOverlayControllerLabelModeTest.kt`: locks main-key mode routing into
  the app-facing JNI keypad snapshot export, the USER top-label composition
  that keeps printed main-key legends, the Virtuoso blank-keycap composition,
  and the Kotlin-side softkey `graphic` and `off` scene masks
- `PhysicalKeyboardInputParityTest.kt`: locks printable, function-key,
  shortcut, and modifier-tap mapping behavior
- `NativeCoreRuntimeTest.kt`: locks single-init, queued-task, and
  save-on-pause behavior on the core thread
- `StorageAccessCoordinatorTest.kt` and `WorkDirectoryTest.kt`: lock the
  first-run welcome-dialog picker route, missing-directory recovery,
  work-directory tree persistence, detached-fd cancellation, and work-directory
  tree subfolder rules
- `HapticFeedbackControllerTest.kt`: locks persisted `haptic_intensity`
  normalization against the XML-declared `0..255` range, zero-intensity
  suppression, and the pre-O legacy click path
- `MainActivityPreferenceControllerTest.kt`: locks persisted `beeper_volume`
  normalization against the XML-declared `0..100` range and the normalized
  `syncAudioSettings(...)` dispatch on startup and preference changes

Use `cd android && ./gradlew :app:testDebugUnitTest` as the smallest grouped
lane when one of those Kotlin- or Robolectric-owned contracts changes.

## Android Instrumentation Contract Suite

The instrumentation suite under
`android/app/src/androidTest/java/io/github/ppigazzini/r47zen/` is the device or emulator
surface for Android-only runtime seams.

Important files include:

- `ProgramLoadTestBridge.kt`: exposes the instrumentation-only native bridge for
  runtime readiness, async function execution, redraw flags, seeding helpers,
  and state snapshots, including a visible-LCD snapshot hash that ignores
  transport dirty flags
- `ProgramFixtureInstrumentedTest.kt`: stages canonical `PROGRAMS` fixtures and
  drives `READP` plus `RUN` through the live Android runtime for
  `BinetV3.p47`, `GudrmPL.p47`, `NQueens.p47`, and `SPIRALk.p47`
- `DisplayLifecycleInstrumentedTest.kt`: locks the lifecycle LCD contract so a
  background save, a Settings-style pause or resume, and full
  `ActivityScenario.recreate()` preserve the visible packed LCD snapshot on
  both a clean display and a staged `SPIRALk` graph, using retrying synthetic
  `00` resumes while paused and a `90 s` hosted-emulator budget for the staged
  graph run
- `FactorsInstrumentedTest.kt`: asserts that the `FACTORS` runtime path runs to
  completion and leaves X in the expected result type
- `GraphRedrawInstrumentedTest.kt`: locks the redraw-gate contract behind
  `forceRefreshNative()`
- `StorageAccessCoordinatorInstrumentedTest.kt`: locks detached-fd handoff and
  cancellation behavior through the Android file-descriptor seam
- `scripts/android/run_16kb_runtime_smoke.sh`: asserts that a connected device
  or emulator reports `16384`-byte pages before it runs the focused activity
  recreation lifecycle probe on the live Android runtime

Use `cd android && ./gradlew :app:assembleDebugAndroidTest` first, then
`cd android && ./gradlew :app:connectedDebugAndroidTest` when the task touches
the Android-only runtime seam.

## Host Regression And Build Contracts

The repo also keeps non-device contract surfaces for the shared core and the
Android compatibility layer.

- `scripts/workload-regressions/run_workload_regressions.sh` builds the staged
  core plus Android bridge in `HOST_TOOL_BUILD` and `PC_BUILD`, then loads and
  runs the canonical workload fixtures through the host compatibility path
- `scripts/workload-regressions/host_workload_regression.c` is the harness that
  probes the wait, pause, progress, and workload-run behavior behind that lane
- `./scripts/android/build_android.sh --run-sim-tests` rebuilds `build.sim`,
  stages `testPgms.bin`, and runs the explicit Android-lane simulator parity
  path before Gradle packaging
- `scripts/upstream-sync/upstream.sh verify-restore-boundary` fails when the
  repo-owned restore allowlist would re-own authoritative upstream root
  surfaces, and `sync` runs that same guard before it restores tracked paths
- the CI workflow keeps three main verification jobs distinct:
  `upstream-simulator-sanity`, `android-build-test-package`, and
  `android-tests`
- `.github/workflows/android-release.yml` reuses the full staged-native build
  path, then runs lint, JVM tests, instrumentation assembly, and
  `:app:bundleRelease` before it publishes signed release evidence

When a change touches staged-core compatibility, `yieldToAndroidWithMs(...)`,
or wait and progress behavior, start with the host workload harness before you
assume the problem is Android UI code.

## Which Lane To Run First

- geometry or label-policy change rooted in `scripts/r47_contracts/`: run the
  grouped contract-script lane first
- keypad-scene export, decoder, renderer, keyboard, or runtime-coordinator
  change in Kotlin: run `cd android && ./gradlew :app:testDebugUnitTest`
- SAF, `READP`, redraw-gate, or other Android-only runtime seam change: run
  `:app:assembleDebugAndroidTest`, then `:app:connectedDebugAndroidTest`
- 16 KB runtime proof on a connected target: run
  `bash ./scripts/android/run_16kb_runtime_smoke.sh`
- sync allowlist or upstream overlay contract change: run
  `bash ./scripts/upstream-sync/upstream.sh verify-restore-boundary`
- pause, wait, progress, or `PC_BUILD` event-loop compatibility change: run
  `scripts/workload-regressions/run_workload_regressions.sh`
- staged-native, simulator, or CI-critical verification change: run
  `./scripts/android/build_android.sh --run-sim-tests`
- release identity, signing, or packaging change: run lint,
  `:app:testDebugUnitTest`, `:app:assembleDebugAndroidTest`, and
  `:app:bundleRelease`, then collect packaging evidence when the release
  artifact contract moves

## Contract Change Rules

- Update the owning contract file and the owning verification surface in the
  same change.
- Keep generated or exported manifest values such as scene contract version,
  metadata length, and labels-per-key aligned across the producer and decoder.
- Treat `ProgramLoadTestBridge.kt` and `jni_program_load_test.c` as a paired
  instrumentation seam.
- When a geometry rule changes, update both the checked-in Python payload logic
  and the Kotlin owner or parity tests that consume it.
- Do not claim a contract change is safe until the smallest relevant local lane
  and the matching CI lane are both identified.
