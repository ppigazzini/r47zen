# Kotlin Shell Architecture

## Top-level model

The Android app is a single-activity, view-based shell around a long-lived
native core. `MainActivity` coordinates Android lifecycle, preferences, slot
selection, settings, and external integrations. It does not own the calculator
engine loop.

## Ownership by class

- `MainActivity`: loads `c47-android`, binds the layout, wires helper types,
  forwards input to the core runtime, and exposes the native methods used by
  JNI.
- `NativeCoreRuntime`: owns the persistent core thread, the shared task queue,
  frame-driven LCD refresh, and keypad snapshot refresh.
- `SlotStore`: owns slot metadata persistence and the current-slot preference.
- `AudioEngine`: owns Android-side tone playback and runtime beeper settings.
- `StorageAccessCoordinator`: owns SAF create and open launcher registration
  during activity initialization, native file request handoff, direct result
  delivery used by tests, and work-directory validation on resume.
- `WorkDirectory`: stores the persisted tree URI in dedicated no-backup
  preferences and resolves the `STATE`, `PROGRAMS`, `SAVFILES`, and `SCREENS`
  subfolders.
- `DisplayActionController`: owns display long-press actions such as copy X
  register, paste number, and entry into Picture-in-Picture.
- `ReplicaOverlay` and `ReplicaKeypadLayout`: host the shell chrome modes, the
  normalized shared keypad touch grid, LCD projection, the classic
  `r47_texture` image-backed shell, the full scene-driven key views used by
  `native`, the scene-driven label overlay used by `r47_background`, the
  shared settings-entry strip, the startup scene-contract guard for dynamic
  keypad refresh, and the PiP interaction surface.
- `KeypadTopology`: owns the Android-local 43-key row, lane, and family
  contract used by layout, touch-grid row membership, and per-key family
  policy.
- `CalculatorKeyView` and `CalculatorSoftkeyPainter`: keep the per-key drawing
  split honest. `CalculatorKeyView` owns main-key geometry, faceplate layout,
  faceplate-offset recomputation after key layout, and painted-body placement,
  while `CalculatorSoftkeyPainter` owns the
  dedicated softkey drawing, overlay, and content-description path.
- `PhysicalKeyboardInputController`, `PhysicalKeyboardMapper`, and
  `PhysicalKeyboardBindingTables`: own external-keyboard interception,
  table-driven bindings, and dispatch into native-key or shortcut actions.
- `SettingsActivity`: owns the non-exported settings UI and preference-driven
  Android shell options, then delegates destructive reset work back to
  `MainActivity`.

## Model boundary

- `KeypadSnapshot` is the Kotlin-side projection of the native keypad scene.
  Its fixed metadata-lane decoding stays local to the snapshot model so other
  Android layers consume named fields instead of indexing raw native arrays.
- `KeypadTopology` is the Android-local keypad topology contract for key-code
  row order, family, and touch-grid lane membership. It is consumed by
  `ReplicaKeypadLayout` and `CalculatorKeyView`, not exported by the native
  calculator core.
- slot metadata is an Android model owned by `SlotStore`, not by the native
  calculator core.
- preference state controls Android shell behavior such as fullscreen mode,
  chrome mode, scaling mode, haptics, beeper volume, and touch-zone debugging.
  `chrome_mode` now chooses between the default `r47_texture` shell, the
  `r47_background` background-backed shell, and the native-drawn shell. All
  three modes share the same logical touch grid, the same texture-derived LCD
  placement, and the same adaptive visible-frame crop in `full_width`.

## Runtime and event flow

Calculator state does not live in `Activity` fields alone. The durable state
owner is the native core plus `NativeCoreRuntime`'s shared thread and task queue.

Main flow:

1. Android touch, keyboard, or menu events call `offerCoreTask(...)` or direct
   bridge methods exposed by `MainActivity`.
2. `NativeCoreRuntime` serializes native work on the core thread.
3. The frame callback pulls LCD pixels and keypad metadata from native code.
4. `MainActivity.currentKeypadSnapshot()` converts native keypad arrays into a
  `KeypadSnapshot`.
5. `ReplicaKeypadLayout` ignores snapshots until `sceneContractVersion > 0`,
  requests layout after scene changes, and `CalculatorKeyView` recomputes its
  fixed faceplate offsets from `onLayout` before the shell redraws.

This keeps the UI reactive while the engine remains on one native execution path.

Visible keypad-label placement keeps fixed per-key geometry plus one
row-local horizontal solve for the shared `f`/`g` group. If a geometry
decision depends on button bounds or neighboring keys in the same keypad lane,
keep it tied to the measured reference-canvas formulas in
`CalculatorKeyView` and only apply the row-local horizontal `centerShift`
after the overlay has a real layout. Neighboring `f`/`g` groups in the same
lane must keep an inter-group gap of at least `2 *` the measured intragap used
inside one `f`/`g` pair, and no group may cross the neighboring key border by
more than five intragaps. Each group therefore owns one explicit horizontal
corridor: from five intragaps before the physical left neighbor's right border
to five intragaps after the physical right neighbor's left border. The first
and last visible groups in a lane also carry hard lateral bounds from
`ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout()`: they must stay
inside the `0 .. overlay.width` smartphone screen edges with no extra corridor
extension beyond those outer sides. The per-key
horizontal shift budget is a preferred placement budget, not a hard
overlap-permitting limit. The live row solver now keeps the intragap fixed and
runs in stages: first try a bounded local move of the current offender, then a
bounded whole-row translation, then fixed-step scale-down on the longest label
of the longest offender, then fixed-step scale-down on the other label if that
same group is still the worst offender down to the preferred
`TOP_F_G_LABEL_MIN_SCALE`, while keeping vertical placement fixed.
`__DEV/R47/compute_top_label_lane_layout.py` is the owner for this lane policy,
and `__DEV/R47/test_top_label_lane_layout.py` plus
`DynamicKeypadParityFixtureTest.kt` lock the focused Python and Kotlin
regressions to the same contract, including the hard outer screen-edge rule. The two
labels inside one `f`/`g` group may differ by at most one fixed scale step.
After any successful scale step, the row solve restarts from centered defaults
so non-offending groups do not stay shifted. If one offending group is already
fully reduced on both labels and the mandatory `2 * gap` inter-group rule is
still broken, the colliding neighboring group becomes the next translation and
scale target. If the row is still unresolved at the preferred minimum, the
solver retries translation with preferred-shift-budget overflow before it keeps
scaling the current worst offender below that minimum.
inter-group gap and the neighbor-border limit, only then may the solver exceed
the preferred shift budget. Do not reintroduce
vertical staggering, broad multi-label adaptive scaling, or pre-contract
snapshot rendering.

Layout-affecting preference changes now follow an explicit renderer process:

- geometry change: request layout, reset the unchanged-snapshot skip, and replay
  the current scene after the next real overlay layout
- draw-only content change: invalidate the affected view so paint-flag and text
  updates redraw immediately

`NativeCoreRuntime` is also the place where LCD refresh cadence and keypad
metadata polling stay coordinated, so do not duplicate refresh loops elsewhere
in the shell.

## Lifecycle contract

- `onCreate()` wires the overlay, helpers, SAF launchers, keypad, and native
  runtime, then attaches the core thread.
- `onNewIntent()` is the reuse path for root-activity actions such as the
  controlled factory-reset request.
- `onResume()` requests a native refresh and revalidates the work-directory
  contract.
- `onPause()` performs a synchronous native save when auto-save on minimize is
  enabled and the app is not moving into PiP or a reset-driven relaunch.
- `onDestroy()` stops the shared runtime when the activity is actually
  finishing, and the factory-reset path also clears internal app data after the
  runtime has been told to stop.
- `onPictureInPictureModeChanged()` switches the overlay between normal shell
  mode and PiP mode.

Activity Result launchers are registered from `MainActivity.onCreate()` through
`StorageAccessCoordinator.registerLaunchers()`. Helper construction must stay
side-effect free after resume so tests can call `deliverNativeFileResult()`
without violating the Activity Result lifecycle contract.

## Input surfaces

The Kotlin shell currently accepts input from four paths:

- on-screen keys built by `ReplicaKeypadLayout`
- accessibility click activation on those same key views once they hold focus
- physical keyboard mappings handled by `PhysicalKeyboardInputController`,
  `PhysicalKeyboardMapper`, and `PhysicalKeyboardBindingTables`
- display long-press actions coordinated by `DisplayActionController`
- PiP touch mapping handled by `ReplicaOverlay`

Each path ultimately resolves to core-thread work or a small Android-side action.

## Persistence and slot model

- slot metadata lives in `SlotStore`
- the active slot ID is mirrored into native code through `setSlotNative(...)`
- slot switching follows one ordered background flow: save current state, update
  slot ID, load target state
- SAF-backed user files remain outside the internal app directory contract
- the work-directory contract is a user-facing SAF tree, not a replacement for
  the app-internal native base path
- manifest backup rules intentionally exclude `R47Slots` and the work-directory
  URI preferences because slot URIs and SAF grants are device-specific; general
  shell preferences remain migratable

## Current platform shape

- the app is view-based and uses view binding
- `MainActivity` remains portrait-first in the manifest for shell fidelity
- the application is explicitly resizable, so large screens and foldables may
  letterbox or window the shell according to Android compatibility behavior
- Picture-in-Picture is enabled
- settings live in a separate non-exported `SettingsActivity`
- haptics, audio, fullscreen state, scaling mode, and touch-zone overlays are
  preference-driven Android concerns

## Kotlin-side change rules

- Keep `MainActivity` as a coordinator, not as a second calculator engine.
- Keep blocking work off the main thread.
- Add new Android integrations through focused helper types when they do not
  belong in `MainActivity`.
- Keep `registerForActivityResult()` calls in the unconditional activity or
  fragment initialization path, not in helper constructors or late callbacks.
- When adding a native call, update the Kotlin external declaration and the JNI
  registration table together.
- When a feature needs both Android lifecycle logic and native execution,
  prefer a small Kotlin coordinator plus core-thread work instead of splitting
  state across both sides.
- If a new feature needs durable calculator state, decide first whether the true
  owner is the native core, Android preferences, or Android-side slot metadata.
