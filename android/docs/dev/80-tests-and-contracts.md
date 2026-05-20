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
| shell geometry, top-right menu bounds, and LCD frame | `scripts/r47_contracts/derive_shell_geometry.py`, `R47Geometry.kt` | `scripts/r47_contracts/test_shell_geometry_contract.py`, `ReplicaOverlayGoldenTest.kt`, `MainShellThemeTest.kt` | grouped `scripts/r47_contracts` validation lane, then `:app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.ReplicaOverlayGoldenTest --tests io.github.ppigazzini.r47zen.MainShellThemeTest` |
| key-label and visual policy constants | `scripts/r47_contracts/derive_key_label_geometry.py`, `scripts/r47_contracts/derive_key_visual_policy.py`, `scripts/r47_contracts/data/r47zen_ui_contract.json`, `KeyRenderSpec.kt`, `KeyRenderPainter.kt`, `CalculatorKeyView.kt`, `CalculatorSoftkeyPainter.kt` | `scripts/r47_contracts/test_key_label_geometry_contract.py`, `scripts/r47_contracts/test_key_visual_policy_contract.py`, `CalculatorKeyViewRenderSpecTest.kt`, `CalculatorSoftkeyPainterCanvasTest.kt` | grouped `scripts/r47_contracts` validation lane, then focused JVM render-spec tests when the Kotlin builder or shared painter stage moved |
| keypad font policy and C47 font coverage | `scripts/r47_contracts/data/r47_key_font_policy_contract.json`, `scripts/r47_contracts/derive_key_font_policy.py`, `ReplicaKeypadLayout.kt`, `CalculatorKeyView.kt`, `CalculatorSoftkeyPainter.kt`, `C47TypefacePolicy.kt` | `scripts/r47_contracts/test_key_font_policy_contract.py`, `CalculatorKeyViewFontSelectionTest.kt` | grouped `scripts/r47_contracts` validation lane, then `:app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.CalculatorKeyViewFontSelectionTest` |
| top-label lane solve and alpha-case label export | `scripts/r47_contracts/derive_top_label_lane_layout.py`, staged `assign.c` and `items.c`, `jni_display.c`, `ReplicaKeypadLayout.kt`, `CalculatorKeyView.kt` | `scripts/r47_contracts/test_top_label_lane_layout_contract.py`, `scripts/r47_contracts/test_alpha_case_export_contract.py`, `DynamicKeypadParityFixtureTest.kt` | grouped contract scripts first, then `:app:testDebugUnitTest` |
| overlay geometry replay for unchanged keypad scenes | `MainActivity.kt`, `ReplicaOverlayController.kt`, `ReplicaOverlay.kt`, `ReplicaKeypadLayout.kt` | `DynamicKeypadParityFixtureTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| keypad scene export manifest and decoder | `KeypadSnapshot`, exported keypad fixtures, `jni_display.c` | `KeypadFixtureContractTest.kt`, `KeypadSnapshotDecoderTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| rendered keypad and softkey semantics | `ReplicaKeypadLayout.kt`, `CalculatorKeyView.kt`, `CalculatorSoftkeyPainter.kt`, `KeyRenderSpec.kt`, `ReplicaOverlayController.kt`, `KeypadLabelModes.kt`, `C47TypefacePolicy.kt` | `CalculatorKeyViewRenderSpecTest.kt`, `CalculatorKeyViewFontSelectionTest.kt`, `ExportedKeypadFixtureRenderTest.kt`, `CalculatorSoftkeyPainterContractTest.kt`, `CalculatorSoftkeyPainterCanvasTest.kt`, `ReplicaOverlayGoldenTest.kt`, `ReplicaOverlayControllerLabelModeTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| physical keyboard mapping | `PhysicalKeyboardMapper`, `PhysicalKeyboardInputController` | `PhysicalKeyboardInputParityTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest` |
| core thread, display loop, and runtime gate behavior | `NativeCoreRuntime.kt`, `NativeDisplayRefreshLoop.kt`, `NativeKeypadSnapshotStore.kt`, `jni_lifecycle.c`, `jni_display.c`, `android_runtime.c` | `NativeCoreRuntimeTest.kt`, `NativeDisplayRefreshLoopTest.kt`, `GraphRedrawInstrumentedTest.kt`, `run_workload_regressions.sh` | focused JVM tests first, then the host workload lane when the compatibility path moved |
| developer performance HUD preference, snapshot transport, and shell visibility | `DeveloperPerformanceSnapshot.kt`, `NativeDisplayRefreshLoop.kt`, `MainActivityPreferenceController.kt`, `MainActivity.kt`, `ReplicaOverlay.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `android/app/src/main/res/values/strings.xml` | `NativeDisplayRefreshLoopTest.kt`, `MainActivityPreferenceControllerTest.kt`, `SettingsPreferenceSummaryTest.kt`, `ReplicaOverlayGoldenTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.NativeDisplayRefreshLoopTest --tests io.github.ppigazzini.r47zen.MainActivityPreferenceControllerTest --tests io.github.ppigazzini.r47zen.SettingsPreferenceSummaryTest --tests io.github.ppigazzini.r47zen.ReplicaOverlayGoldenTest` |
| stop delivery and first-stop LCD cleanup during long-running program execution | `MainActivity.kt`, `LiveProgramStopKeyPolicy.kt`, `ProgramLoadTestBridge.kt`, `jni_program_load_test.c`, `ReplicaOverlayController.kt`, `NativeDisplayRefreshLoop.kt`, `NativeKeypadSnapshotStore.kt`, `NativeCoreRuntime.kt`, `jni_display.c`, `jni_input.c`, `jni_lifecycle.c`, `android_runtime.c` | `LiveProgramStopKeyPolicyTest.kt`, `NativeDisplayRefreshLoopTest.kt`, `ReplicaOverlayControllerLabelModeTest.kt`, `ProgramFixtureInstrumentedTest.kt`, `DisplayLifecycleInstrumentedTest.kt`, `scripts/workload-regressions/run_workload_regressions.sh`, and focused JVM stop-routing checks | rerun `LiveProgramStopKeyPolicyTest` first, then the host workload lane, then `:app:assembleDebugAndroidTest` plus `:app:connectedDebugAndroidTest` when the Android-owned stop or first-stop refresh seam moved |
| settings lifecycle and activity recreation LCD preservation | `MainActivity.kt`, `NativeCoreRuntime.kt`, `jni_activity_bridge.c`, `jni_lifecycle.c`, `ProgramLoadTestBridge.kt` | `DisplayLifecycleInstrumentedTest.kt`, `scripts/android/run_16kb_runtime_smoke.sh` | `:app:assembleDebugAndroidTest` plus `:app:connectedDebugAndroidTest`, or `bash ./scripts/android/run_16kb_runtime_smoke.sh` when 16 KB runtime proof matters |
| settings behavior copy, main-shell menu copy, follow-up copy-popup parity, developer performance HUD copy, fixed dark shell and popup theme invariants, the explicit checked settings-switch orange-track and blue-thumb tint mapping, adaptive settings host layout, and dependent keypress haptic default-toggle plus custom-duration copy | `SettingsActivity.kt`, `SettingsSwitchPreference.kt`, `MainActivity.kt`, `DisplayActionController.kt`, `android/app/src/main/cpp/r47zen/jni_display.c`, `android/app/src/main/cpp/r47zen/android_helpers.c`, `android/app/src/main/res/layout/settings_activity.xml`, `android/app/src/main/res/layout-w600dp/settings_activity.xml`, `android/app/src/main/res/xml/root_preferences.xml`, `android/app/src/main/res/values/strings.xml`, `AndroidManifest.xml`, `android/app/src/main/res/values/themes.xml` | `DisplayActionControllerTest.kt`, `SettingsActivityThemeTest.kt`, `SettingsPreferenceSummaryTest.kt`, `MainShellThemeTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.DisplayActionControllerTest --tests io.github.ppigazzini.r47zen.SettingsActivityThemeTest --tests io.github.ppigazzini.r47zen.SettingsPreferenceSummaryTest --tests io.github.ppigazzini.r47zen.MainShellThemeTest` |
| keypad haptic gate, Android-default toggle versus custom `0..100 ms` override, and press-only keypad cadence | `HapticFeedbackController.kt`, `ReplicaKeypadLayout.kt`, `MainActivity.kt`, `SettingsActivity.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `android/app/src/main/res/values/strings.xml`, `AndroidManifest.xml` | `HapticFeedbackControllerTest.kt`, `ReplicaKeypadLayoutHapticsTest.kt`, `SettingsPreferenceSummaryTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.HapticFeedbackControllerTest --tests io.github.ppigazzini.r47zen.ReplicaKeypadLayoutHapticsTest --tests io.github.ppigazzini.r47zen.SettingsPreferenceSummaryTest` |
| beeper volume normalization and audio settings dispatch | `MainActivityPreferenceController.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `MainActivity.kt` | `MainActivityPreferenceControllerTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.MainActivityPreferenceControllerTest` |
| LCD display theme normalization, inverse polarity, and palette contrast | `LcdThemePolicy.kt`, `MainActivityPreferenceController.kt`, `MainActivity.kt`, `android/app/src/main/res/xml/root_preferences.xml`, `android/app/src/main/res/values/arrays.xml`, `android/app/src/main/res/values/strings.xml` | `LcdThemePolicyTest.kt`, `MainActivityPreferenceControllerTest.kt`, `SettingsPreferenceSummaryTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.LcdThemePolicyTest --tests io.github.ppigazzini.r47zen.MainActivityPreferenceControllerTest --tests io.github.ppigazzini.r47zen.SettingsPreferenceSummaryTest` |
| main shell visible bars, fixed shell-menu copy, orange-blue touch-target placement, copy-popup shape, first-touch discovery-hint dismissal, and any retained discovery-hint surfaces | `MainActivity.kt`, `DisplayActionController.kt`, `WindowModeController.kt`, `ReplicaOverlay.kt`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml` | `DisplayActionControllerTest.kt`, `MainShellThemeTest.kt`, `ReplicaOverlayVisualPolicyTest.kt` | `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.DisplayActionControllerTest --tests io.github.ppigazzini.r47zen.MainShellThemeTest --tests io.github.ppigazzini.r47zen.ReplicaOverlayVisualPolicyTest` |

`Theme.R47` and `Theme.R47.PopupMenu` are intentional fixed-dark contracts.
If a future change makes the shell or popup menus follow the phone light or
dark setting again, treat that as a regression and restore these tests before
merging.
| SAF picker, startup work-directory routing, detached-fd handoff, and work-directory tree persistence | `StorageAccessCoordinator.kt`, `SettingsActivity.kt`, `WorkDirectory.kt`, `jni_storage.c`, `hal/io.c` | `StorageAccessCoordinatorTest.kt`, `WorkDirectoryTest.kt`, `StorageAccessCoordinatorInstrumentedTest.kt` | JVM tests first, then `:app:assembleDebugAndroidTest` and instrumentation when the Android-only seam moved |
| program load and run through Android READP | `android/app/build.gradle` `requestedProgramFixtureNames`, `ProgramLoadTestBridge.kt`, `jni_program_load_test.c`, staged `PROGRAMS` fixtures | `ProgramFixtureInstrumentedTest.kt`, `FactorsInstrumentedTest.kt` | `:app:assembleDebugAndroidTest` plus `:app:connectedDebugAndroidTest` |
| pause, wait, and progress compatibility in `PC_BUILD` mode | `android_runtime.c`, staged core, workload harness | `scripts/workload-regressions/run_workload_regressions.sh`, `host_workload_regression.c` | host workload regression, then `./scripts/android/build_android.sh --run-sim-tests --collect-host-pgo --validate-release-pgo` when the collector-driven CI contract moved |
| upstream sync restore boundary | `scripts/upstream-sync/upstream.sh` | `scripts/upstream-sync/upstream.sh verify-restore-boundary` | `bash ./scripts/upstream-sync/upstream.sh verify-restore-boundary` |

## Python Contract Suite

The canonical R47 contract suite lives under `scripts/r47_contracts/`.
Run it from the repo-managed Python environment with `uv run --group dev ...`
so `ruff`, `ty`, and the `fontTools`-backed font derivation scripts all use
the same maintained dependency set.
It keeps two canonical checked-in source files:
`scripts/r47_contracts/data/r47_physical_geometry.json` for measured
physical-R47 geometry and
`scripts/r47_contracts/data/r47zen_ui_contract.json` for the implemented
Android logical canvas, chrome, key-surface, label layout, and solver policy.
The font pass adds a third checked-in contract document,
`scripts/r47_contracts/data/r47_key_font_policy_contract.json`, for the shipped
C47 font asset names, lane fallback policy, runtime cmap expectations, and the
live keypad-label coverage corpus that backs the standard-first keypad rule.
Each `font_fallback_policy` array is ordered by runtime preference, not
alphabetically.
The same JSON keeps `numeric` in `font_assets` and lane `font_coverage` as a
measured font alias for coverage evidence, but `numeric` is not part of the
runtime fallback arrays.
When a historical external GIMP export needs checking, pass its JSON path directly to
`validate_geometry_dataset.py`; no checked-in GIMP dataset exists in this
repository.

The grouped Python lane currently covers:

- `validate_geometry_dataset.py`: structural and spacing checks for the
  physical dataset plus Android UI contract validation against `R47Geometry.kt`,
  `R47KeypadPolicy.kt`, and `CalculatorKeyView.kt`; this now includes the
  native-only `chrome.lcd_windows.native` contract, the top-right
  `chrome.main_menu_button` rectangle, the native `400 x 240` aspect-ratio
  lock, integer width and height for native mode, and the rule that
  `chrome.lcd_windows` exposes only the native LCD window
- `derive_touch_grid.py`: shared touch-grid payload derivation from measured key
  centers
- `test_shell_geometry_contract.py`: logical canvas, the visible main-menu
  button rectangle, the native LCD rectangle, the native LCD aspect-ratio and
  centered integer-bounds contract, and shell constants against
  `R47Geometry.kt`
- `test_key_label_geometry_contract.py`: key-label and key-surface constants,
  the shared render-spec vocabulary, the main-key left-anchored body-layout
  seam, the shared `KeyRenderPainter` stage, and softkey render-spec geometry
  fields, against `CalculatorKeyView.kt`, `CalculatorSoftkeyPainter.kt`,
  `KeyRenderSpec.kt`, `KeyRenderPainter.kt`, `R47KeypadPolicy.kt`, and
  `R47Geometry.kt`
- `test_top_label_lane_layout_contract.py`: spacing, corridor, screen-edge, and
  scale rules for the top-label solver
- `test_key_visual_policy_contract.py`: visual-policy constants against
  `R47KeypadPolicy.kt`
- `derive_key_font_policy.py`: runtime font asset paths, Unicode cmaps,
  fallback-owner snippets, and lane-by-lane keypad label coverage against the
  exported keypad fixtures
- `test_key_font_policy_contract.py`: the checked-in keypad font-policy JSON
  against the live Python-derived payload and Kotlin owner snippets
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
  alpha-layout behavior, layout-class-sensitive keypad rendering,
  render-spec stability for rejected snapshots, and controller-owned
  same-snapshot replay after PiP exit
- `CalculatorKeyViewFontSelectionTest.kt`: locks the Android-local
  standard-first policy so primary, top-label, and fourth-label text stay on
  the standard calculator font and only fall back to the tiny font when the
  standard face is unavailable; the Python font-policy contract keeps the
  shipped font data, lane fallback chains, and keypad corpus in sync with that
  JVM proof surface
- `C47TextRendererTest.kt`: locks the shared text-paint flags, fitted-text
  minimum scale, and the equivalence between the fitted-label path and a direct
  label-spec build at the resolved final size
- `KeyRenderSpecSlotTest.kt`: locks the typed Kotlin label-slot and adornment-
  slot accessors while preserving the stable serialized `id` strings consumed
  by the Python and JSON-backed contract surfaces
- `ExportedKeypadFixtureRenderTest.kt`: proves exported keypad fixtures apply to
  both main keys and softkeys in the live renderer path
- `CalculatorKeyViewRenderSpecTest.kt`: locks the spec-layer main-key body
  bounds, including the left-anchored percent-width body layout, primary
  anchor, top-label group bounds, fourth-label anchor, detached mirror-sync
  bounds, and accessibility text before pixels are drawn
- `CalculatorSoftkeyPainterContractTest.kt` and
  `CalculatorSoftkeyPainterCanvasTest.kt`: lock softkey content-description,
  overlay, preview, strike rendering rules, the grey-32 empty-slot versus
  grey-64 populated-slot fill split, including dotted-row and preview-target
  decorations on native empty slots, the lower-left to upper-right barred
  strike-out slash, the shared softkey text-paint antialias bit, and the
  spec-layer value-field and overlay geometry seam
- `SoftkeyOverlayPainterTest.kt`: locks the extracted overlay-painter stage for
  the menu-badge label and underline path without routing through the larger
  softkey renderer owner
- `ReplicaOverlayGoldenTest.kt`: keeps the native shell rendering stable
  through golden hashes, locks the removal of the old full-width top-bezel
  interception, and covers developer-performance HUD visibility when the
  setting is enabled
- `MainShellThemeTest.kt`: locks the `WindowModeController` PiP request to the
  native LCD `400 x 240` aspect ratio and keeps the visible-system-bar theme
  contract covered in the same focused JVM lane while also locking the fixed
  shell-menu copy and the retained dark settings-discovery hint surfaces in
  light system mode
- `ReplicaOverlayControllerLabelModeTest.kt`: locks main-key mode routing into
  the app-facing whole-snapshot export, the landed single-snapshot USER-mode
  contract that still keeps printed main-key legends, the Virtuoso blank-keycap
  composition, and the Kotlin-side softkey `graphic` and `off` scene masks,
  including the requirement that those masks keep enabled blank capsules
  distinct from native empty-slot scenes
- `PhysicalKeyboardInputParityTest.kt`: locks printable, function-key,
  shortcut, and modifier-tap mapping behavior
- `NativeCoreRuntimeTest.kt`: locks single-init, queued-task,
  save-on-pause, and native-deadline waiting behavior on the core thread
- `NativeDisplayRefreshLoopTest.kt`: locks packed-display generation gating,
  unchanged-frame skip behavior, keypad-generation retry-after-busy-copy,
  cached-snapshot reuse, no-synthetic-empty refresh behavior, and recent-window
  developer performance snapshot publication on the UI observer loop
- `StorageAccessCoordinatorTest.kt` and `WorkDirectoryTest.kt`: lock the
  first-run welcome-dialog picker route, missing-directory recovery,
  work-directory tree persistence, detached-fd cancellation, and work-directory
  tree subfolder rules
- `SettingsActivityThemeTest.kt`: locks the settings-owned dark surface theme
  contract, the explicit checked switch tint mapping of orange track plus blue
  thumb, and the wide-window `layout-w600dp` host layout that centers the
  preferences inside a bounded Material panel
- `SettingsPreferenceSummaryTest.kt`: locks fixed settings copy, including the
  developer performance HUD summary, without depending on runtime on/off state
- `HapticFeedbackControllerTest.kt` and `ReplicaKeypadLayoutHapticsTest.kt`:
  lock the `haptic_enabled` gate, the `haptic_use_android_default` toggle, the
  `haptic_keypress_duration_ms` `0..100` clamp, the Android-default versus
  custom override split, the press-only view-based
  `HapticFeedbackConstants.VIRTUAL_KEY` keypad cadence, the
  release-without-haptic path, the cancel-without-haptic path, and the
  predefined-vibrator or short one-shot override when the view path declines,
  when the user opts into a custom keypress-duration override, or when custom
  mode is set to `0 ms`
- `MainActivityPreferenceControllerTest.kt`: locks persisted `beeper_volume`
  normalization against the XML-declared `0..100` range, plus `lcd_theme`
  fallback to the supported display-theme set, legacy `lcd_mode` migration,
  `lcd_luminance` clamp to the XML-declared `20..120` range, `lcd_negative`
  dispatch, `show_developer_performance_hud` dispatch, and deferred overlay
  apply and preference-change dispatch
- `LcdThemePolicyTest.kt`: locks unknown theme fallback to the default display
  theme and keeps every shipped normal and inverse LCD palette above its
  declared contrast floor across the supported luminance range

Use `cd android && ./gradlew :app:testDebugUnitTest` as the smallest grouped
lane when one of those Kotlin- or Robolectric-owned contracts changes.

## Android Instrumentation Contract Suite

The instrumentation suite under
`android/app/src/androidTest/java/io/github/ppigazzini/r47zen/` is the device or emulator
surface for Android-only runtime seams.

Important files include:

- `ProgramLoadTestBridge.kt`: exposes the instrumentation-only native bridge for
  runtime readiness, async function execution, redraw flags, seeding helpers,
  direct-stop publication, and state snapshots, including a visible-LCD
  snapshot hash that ignores transport dirty flags
- `ProgramFixtureInstrumentedTest.kt`: stages canonical `PROGRAMS` fixtures and
  exposes one Android test method per required fixture so the hosted emulator
  lane can run `BinetV3.p47`, `GudrmPL.p47`, `MANSLV2.p47`, `NQueens.p47`, and
  `SPIRALk.p47` as isolated filtered `connectedDebugAndroidTest` selections
- `scripts/android/run_connected_android_tests.sh`: owns the hosted emulator
  wrapper that runs the four non-fixture instrumentation classes as one
  grouped class-filter selection and executes each canonical `PROGRAMS`
  fixture under GNU `timeout --kill-after`, logging degraded coverage for the
  non-`MANSLV2` selections instead of wedging the whole Android step when one
  hangs
- The connected-device lane now includes a required `MANSLV2.p47`
  bounded-stop regression inside that shared per-fixture wrapper: after
  observed post-load activity it requests a direct stop through
  `ProgramLoadTestBridge.requestStopProgram()`, which reuses the same upstream
  `fnStopProgram(0)` publisher as live `R/S` and `EXIT`. The Android fixture
  now resets every staged run to the upstream `doFnReset(CONFIRMED, false)`
  baseline before load and skips blocked state snapshots instead of stalling
  the test thread behind `screenMutex`, so the harness can keep publishing the
  bounded stop request while the shared core is busy. That proves the required
  Android bounded-stop delivery path through the owned seam, and the hosted
  wrapper now fails the lane if `MANSLV2` times out instead of downgrading it
  to degraded coverage. The remaining gap is narrower: there is still no
  focused device lane proving that a
  forced-busy keypad snapshot path stays responsive under every non-yielding
  shared-core loop.
- `DisplayLifecycleInstrumentedTest.kt`: locks the lifecycle LCD contract so a
  background save, a Settings-style pause or resume, and full
  `ActivityScenario.recreate()` preserve the visible packed LCD snapshot on
  both a clean display and a staged `SPIRALk` graph, and it also proves that
  the first direct stop on staged `SPIRALk` already matches an explicit
  `forceRefresh()` snapshot, using retrying synthetic `00` resumes while paused
  and a `90 s` hosted-emulator budget for the staged graph run
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
  core plus Android bridge in `HOST_TOOL_BUILD` and `PC_BUILD`, then runs the
  canonical host workload set through the host compatibility path: the
  imported `.p47` fixtures `BinetV3.p47`, `GudrmPL.p47`, `MANSLV2.p47`,
  `NQueens.p47`, and `SPIRALk.p47`. Every imported fixture now runs in its own
  host process under the same outer timeout-and-kill safety net, while
  `MANSLV2` remains the bounded direct-stop-after-activity probe inside that
  framework. This is the focused host compatibility rerun surface, not the
  normal pull-request owner of the collector-driven host-core PGO contract
- That host probe does not prove the Android stop-delivery or UI-thread ANR
  contract. It does prove that the shared compatibility path can start the five
  imported fixtures and accept a bounded direct stop for `MANSLV2`, or record
  degraded coverage for any individual hung workload without hanging the lane,
  before Android-shell responsiveness enters the picture.
- That host-only compatibility path does not widen the Android emulator
  `PROGRAMS` fixture matrix.
- The maintained PGO collector now uses a separate merged profile surface: the
  `broad-ci` `testSuite` base of `programs`, `tvm`, `jacobi_audit`,
  `normal_i`, `gamma`, `trig`, `prime`, `factorial`, and the generated
  `matrix_prefix_85` slice from `src/testSuite/tests/matrix.txt`, plus the
  imported `.p47` fixture overlay through the host compatibility path, so the
  compatibility harness and the CI PGO corpus are intentionally different
  verification surfaces.
- `scripts/workload-regressions/collect_host_pgo_profile.sh` builds upstream
  `src/testSuite/testSuite` in Meson `release` with the pinned NDK Clang,
  ThinLTO, and LLVM IRPGO instrumentation, stages
  `res/testPgms/testPgms.bin` into a runtime root for the `programs.txt`
  cases, runs the imported `.p47` fixture overlay through the host
  compatibility path, reuses a host-installed `libclang_rt.profile` archive
  through a temporary resource-dir shim, and merges the resulting raw profiles
  into the indexed `.profdata` artifact consumed by the Android release-native
  build
- `scripts/workload-regressions/host_workload_regression.c` is the harness that
  probes the wait, pause, progress, and workload-run behavior behind that lane
- `./scripts/android/build_android.sh --run-sim-tests --collect-host-pgo --validate-release-pgo`
  is now the normal pull-request owner of the Android wrapper testSuite rerun,
  the host-side PGO collector, and the Android release-native PGO consumer
  check in one lane. The lower-level collector script remains available for
  focused debugging.
- `scripts/upstream-sync/upstream.sh verify-restore-boundary` fails when the
  repo-owned restore allowlist would re-own authoritative upstream root
  surfaces, and `sync` runs that same guard before it restores tracked paths
- the CI workflow keeps three main verification jobs distinct:
  `upstream-simulator-sanity`, `android-build-test-package`, and
  `android-tests`
- `.github/workflows/android-release.yml` reruns the same wrapper-owned
  host-core optimization flow as `android-build-test-package`, then runs lint,
  JVM tests, instrumentation assembly, and
  `:app:bundleRelease -Pr47.pgoProfilePath=...` before it publishes signed
  release evidence

When a change touches staged-core compatibility, `yieldToAndroidWithMs(...)`,
or wait and progress behavior, start with the host workload harness before you
assume the problem is Android UI code.

## Which Lane To Run First

- geometry or label-policy change rooted in `scripts/r47_contracts/`: run the
  grouped contract-script lane first
- keypad-scene export, decoder, renderer, keyboard, or runtime-coordinator
  change in Kotlin: run `cd android && ./gradlew :app:testDebugUnitTest`
- SAF, `READP`, redraw-gate, direct-stop LCD cleanup, or other Android-only
  runtime seam change: run
  `:app:assembleDebugAndroidTest`, then `:app:connectedDebugAndroidTest`
- 16 KB runtime proof on a connected target: run
  `bash ./scripts/android/run_16kb_runtime_smoke.sh`
- sync allowlist or upstream overlay contract change: run
  `bash ./scripts/upstream-sync/upstream.sh verify-restore-boundary`
- pause, wait, progress, or `PC_BUILD` event-loop compatibility change: run
  `scripts/workload-regressions/run_workload_regressions.sh`
- host-core PGO collector or release-native consumer change: run
  `./scripts/android/build_android.sh --run-sim-tests --collect-host-pgo --validate-release-pgo`
- staged-native, simulator, or CI-critical verification change: run
  `./scripts/android/build_android.sh --run-sim-tests`
- release identity, signing, or packaging change: run lint,
  `:app:testDebugUnitTest`, `:app:assembleDebugAndroidTest`,
  `./scripts/android/build_android.sh --run-sim-tests --collect-host-pgo --validate-release-pgo`, and
  `:app:bundleRelease -Pr47.pgoProfilePath=/abs/path/to/r47-host-core.profdata`,
  then collect packaging evidence when the release artifact contract moves

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
