# Iteration 99 - Unified Key Painter Render-Spec Contract

## Requested outcome

- Complete the unified key painter plan from `__DEV/PROMPT.md` and
  `__DEV/reports/REPORT-11-UNIFIED-KEY-PAINTER.md` in one pass.
- Move both main keys and softkeys to a spec-first draw path.
- Keep keypad behavior, label-mode policy, and native scene ownership intact.
- Promote the final contract into maintained Android docs and Python contracts.
- End with broad Kotlin, test, and Python verification, not only focused slices.

## Scope

- `android/app/src/main/java/io/github/ppigazzini/r47zen/KeyRenderSpec.kt`
- `android/app/src/main/java/io/github/ppigazzini/r47zen/KeyRenderPainter.kt`
- `android/app/src/main/java/io/github/ppigazzini/r47zen/C47TextRenderer.kt`
- `android/app/src/main/java/io/github/ppigazzini/r47zen/CalculatorSoftkeyPainter.kt`
- `android/app/src/main/java/io/github/ppigazzini/r47zen/CalculatorKeyView.kt`
- `android/app/src/test/java/io/github/ppigazzini/r47zen/CalculatorSoftkeyPainterCanvasTest.kt`
- `android/app/src/test/java/io/github/ppigazzini/r47zen/CalculatorKeyViewRenderSpecTest.kt`
- `android/app/src/test/java/io/github/ppigazzini/r47zen/DynamicKeypadParityFixtureTest.kt`
- `scripts/r47_contracts/derive_key_label_geometry.py`
- `scripts/r47_contracts/derive_key_font_policy.py`
- `scripts/r47_contracts/test_key_label_geometry_contract.py`
- `scripts/r47_contracts/test_alpha_case_export_contract.py`
- `scripts/r47_contracts/data/r47_android_ui_contract.json`
- `android/docs/dev/40-ui-rendering-and-gtk-mapping.md`
- `android/docs/dev/80-tests-and-contracts.md`
- `__DEV/iters/ITERATION-99.md`

## Constraints

- Implement phases 0 through 9 in one iteration, not as a partial checkpoint.
- Keep the native keypad snapshot contract unchanged.
- Keep the top-label lane solver and alpha-layout behavior intact.
- Preserve Android-owned renderer quality controls such as font policy,
  accessibility text, and pressed-state polish.
- Under `__DEV`, update only the working doc for this pass.
- Because Kotlin changed, finish with Android lint plus a real Gradle build
  lane before calling the task done.

## Non-goals

- No Compose migration.
- No native scene-export schema change.
- No attempt to delete every compatibility `TextView` mirror in this pass.
- No new user-facing documentation.
- No visual redesign beyond the existing geometry and draw-policy contract.

## Acceptance criteria

- Both key families resolve a shared immutable render spec before drawing.
- Main-key and softkey geometry seams are locked by focused JVM tests.
- The Python contract lane understands the new render-spec vocabulary and the
  new softkey geometry constants.
- Maintained docs route debugging through the render-spec seam first.
- Broad Python and Android verification pass in the live workspace.

## Baseline behavior and evidence

- `REPORT-11-UNIFIED-KEY-PAINTER.md` correctly identified the core problem:
  main keys still depended on retained label-view geometry while softkeys were
  already painter-owned but on a separate geometry path.
- Before this pass, `CalculatorKeyView` still encoded runtime geometry through
  child-view layout and translation state.
- Before this pass, `CalculatorSoftkeyPainter` owned draw behavior directly but
  did not expose a shared spec model that contracts or tests could inspect.
- Before this pass, `test_key_label_geometry_contract.py` locked old source
  snippets tied to translation formulas instead of the architectural seam that
  actually decided render output.
- Before this pass, the maintained docs still described the key renderer as a
  retained-label-view pipeline instead of a shared spec-first painter path.

## Official references checked on 2026-05-17

- Android custom drawing:
  https://developer.android.com/develop/ui/views/layout/custom-views/custom-drawing
- Android custom-view hot-path optimization:
  https://developer.android.com/develop/ui/views/layout/custom-views/optimizing-view
- How Android draws views:
  https://developer.android.com/guide/topics/ui/how-android-draws
- Android custom-view accessibility:
  https://developer.android.com/guide/topics/ui/accessibility/views/custom-views

Key takeaways used here:

- Android's custom-drawing guidance says to create `Paint` and related drawing
  objects ahead of `onDraw()` rather than allocating them in the hot path.
- Android's custom-drawing guidance says size-dependent geometry belongs in
  `onSizeChanged()` or other size-bound recomputation paths, not in ad hoc draw
  work repeated every frame.
- Android's custom-view optimization guidance says to eliminate allocations in
  `onDraw()`, minimize unnecessary `invalidate()` calls, and treat stray
  `requestLayout()` churn as a first-line performance smell.
- Android's view draw-lifecycle guidance says geometry ownership should respect
  the measure/layout/draw split: layout work requests layout, while visual-only
  updates invalidate without reopening measurement.
- Android's custom-view accessibility guidance says a custom view must expose
  meaningful accessibility state and events through the view accessibility API,
  not only through visible text.
- Android's custom-view accessibility guidance says custom controls should treat
  DPAD center and Enter like touch activation.

These references support the design here:

- precreated shared paints in `C47TextRenderer` and the painter helpers
- geometry recomputation through render-spec refresh on layout and size changes
- render-spec-owned accessibility descriptions for main keys and softkeys

## Options

### Option 1: keep retained label views as the real main-key geometry owner

Pros:

- smallest code churn in `CalculatorKeyView`
- fewer contract updates in the short term

Cons:

- preserves the architectural split that caused the report
- keeps runtime geometry implicit in widget state instead of an inspectable
  render seam
- makes softkey and main-key parity harder to reason about

### Option 2: shared spec-first renderer with detached compatibility mirrors

Pros:

- one render vocabulary for both key families
- contracts and tests can inspect geometry before pixels are drawn
- keeps compatibility hooks available while moving runtime ownership to specs

Cons:

- requires coordinated Kotlin, test, Python-contract, and doc updates
- some legacy parity tests need to move from mirror geometry assertions to
  render-spec assertions

### Option 3: immediate pure-painter cutover with no compatibility mirrors

Pros:

- cleanest final ownership model
- removes legacy view-state surfaces immediately

Cons:

- larger blast radius across parity tests and helper APIs
- unnecessary risk for a phase-driven experimental iteration

## Role viewpoints

Software architect:

- Option 2 is the right boundary. It centralizes render decisions in a small,
  immutable model without forcing every compatibility seam to disappear in the
  same patch.

Android platform engineer:

- Option 2 preserves the custom-view contract: prebuilt paints, layout-driven
  geometry refresh, explicit accessibility text, and no new dependency on
  transient child-widget draw behavior.

Build and release engineer:

- Option 2 is the smallest change that still promotes the new seam into the
  Python and JVM verification lanes and into the maintained docs.

Technical writer:

- Option 2 gives docs a single sentence that is now true: main keys and
  softkeys both build `KeyRenderSpec` first, then paint from it.

## Recommendation

Ship Option 2.

- Add a shared render-spec vocabulary.
- Refactor softkeys first, then main keys, onto that vocabulary.
- Keep detached `TextView` mirrors only as compatibility holders for tests and
  font-policy state.
- Move contracts and docs to the render-spec seam instead of the old
  translation-based implementation details.

## Discussion and nitpicks

- The old Python contract lane was more tightly coupled to source snippets than
  the report implied. This pass had to update `derive_key_font_policy.py` and
  the alpha-layout contract tests so the suite locked the new seam instead of
  forcing dead implementation details back into the code.
- `DynamicKeypadParityFixtureTest` needed an extra `measure/layout/idle` cycle
  to stabilize the settled render spec before comparing pre-change and
  post-rejected-snapshot state. The spec-first path is still correct, but the
  test now has to account for the extra layout pass that finalizes it.
- Detached mirror views remain intentionally non-authoritative. They still
  expose text, paint, and compatibility state, but runtime geometry now starts
  from `KeyRenderSpec`.

## Planning and phase closure

### Phase 0: establish the seam

- Added `KeyRenderSpec.kt` with shared immutable render types for chrome,
  labels, adornments, accessibility, top-label groups, and per-family geometry.
- Added `CalculatorKeyViewRenderSpecTest.kt` to lock a representative main-key
  render spec before pixels are drawn.

### Phase 1: softkey spec builder

- Refactored `CalculatorSoftkeyPainter` so `draw(...)` now resolves a softkey
  `KeyRenderSpec` first.
- Captured softkey value-field bounds, overlay center, preview line, and strike
  geometry in spec form.

### Phase 2: main-key spec builder

- Refactored `CalculatorKeyView` so main keys resolve one render spec through
  `buildMainKeyRenderSpec()` and draw from that resolved model.
- Moved body bounds, primary anchor, top-label group, and fourth-label anchor
  into `MainKeyGeometrySpec`.

### Phase 3: shared painter primitives

- Added `KeyRenderPainter.kt` to centralize chrome, label, and line drawing.
- Kept text measurement and paint configuration in `C47TextRenderer` so both
  key families share one text-rendering policy.

### Phase 4: compatibility mirrors

- Kept `primaryLabel`, `fLabel`, `gLabel`, and `letterLabel` as detached
  compatibility mirrors.
- Removed them as runtime geometry owners.

### Phase 5: accessibility and content description ownership

- Main-key accessibility text now comes from the resolved main-key render spec.
- Softkey accessibility text remains painter-owned but now follows the same
  spec-first path.

### Phase 6: focused geometry tests

- Extended `CalculatorSoftkeyPainterCanvasTest.kt` with the softkey geometry
  seam assertion for value-field bounds and overlay center.
- Added the main-key render-spec seam test.

### Phase 7: Python contract promotion

- Updated `derive_key_label_geometry.py` and
  `test_key_label_geometry_contract.py` to lock the new render-spec vocabulary,
  main-key formulas, and softkey geometry fields.
- Promoted the new softkey geometry constants into
  `r47_android_ui_contract.json`.

### Phase 8: broader contract and parity promotion

- Updated `derive_key_font_policy.py` for the shared painter path.
- Updated `test_alpha_case_export_contract.py` for the width-fraction alpha
  spacer model and the explicit render-spec refresh step.
- Updated `DynamicKeypadParityFixtureTest.kt` so the rejected-snapshot parity
  assertion compares the resolved render spec and stable visible text instead
  of the old mirror-translation seam.

### Phase 9: maintained docs and debugging route

- Updated `android/docs/dev/40-ui-rendering-and-gtk-mapping.md` so it now names
  `CalculatorKeyView.buildMainKeyRenderSpec()` and
  `CalculatorSoftkeyPainter.buildRenderSpec()` as the first geometry owners.
- Updated `android/docs/dev/80-tests-and-contracts.md` so the contract map now
  includes `KeyRenderSpec.kt`, `CalculatorKeyViewRenderSpecTest.kt`, and the
  spec-layer softkey seam.
- Re-read both maintained docs after the final patch and left them aligned with
  the live code.

## Verification

- Workspace task `shell: r47-run-python-scripts`

  Result:
  final pass succeeded, including `ruff`, `ty`, geometry validation, payload
  derivation, and 50 Python unit tests.

- Focused parity rerun:
  `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.DynamicKeypadParityFixtureTest.iteration66_nonContractSnapshotDoesNotOverwriteRenderedKey`

  Result:
  passed after the parity test moved to the render-spec seam and the extra
  settle pass.

- Broad Android lane:
  `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew lint :app:testDebugUnitTest assembleDebug`

  Result:
  passed. `lint` wrote the HTML report to
  `android/app/build/reports/lint-results-debug.html`, the full debug unit-test
  suite passed, and `assembleDebug` completed successfully.

Verification notes:

- The first grouped Python rerun caught stale source-contract expectations in
  the font-policy and alpha-layout contract tests. Those were updated to lock
  the new seam, then the full task passed.
- The first broad Android rerun caught a parity fixture assumption on mirror
  translations. The focused rerun moved that test to the render-spec seam, and
  the final broad lane then passed.

## Post-implementation stabilization addendum

- Added `test_shared_painter_stage_remains_explicit` to
  `scripts/r47_contracts/test_key_label_geometry_contract.py` so the shared
  `KeyRenderPainter` seam is now contract-owned for main-key chrome plus
  softkey preview and strike lines.
- Updated maintained Android docs and internal reference docs so the live owner
  map now covers `KeyRenderSpec`, `KeyRenderPainter`, the restored left-
  anchored main-key body-layout seam, and the May 2026 custom-view hot-path
  guidance.
- Updated `REPORT-11-UNIFIED-KEY-PAINTER.md` with an implementation-status note
  so the report stays a historical decision record instead of reading like the
  plan is still pending.
- The remaining extra contract that was worth adding now was the shared
  painter-stage guard above. More snippet-level source contracts would add
  noise faster than protection until the next ownership cleanup exposes a new
  stable seam.

## Remaining risks and follow-ups

- The detached mirror views are still compatibility scaffolding. A later cleanup
  can remove them only after every remaining parity and helper surface stops
  depending on them.
- Some Python contracts still inspect source snippets as a guardrail. That is
  workable, but future renderer refactors should prefer payload- and behavior-
  level checks over text-snippet coupling when possible.
- If later accessibility work needs per-label virtual focus or richer spoken
  state, add explicit `AccessibilityNodeInfo` ownership on top of the existing
  spec-owned content descriptions instead of drifting back to child-view
  geometry ownership.

## Annex A - Custom-painter codebase analysis and lean follow-up refactors

### What is now strong

- The repo finally has one explicit `snapshot -> spec -> canvas` seam for both
  key families.
- Shared paints live outside `onDraw()`, and the main-key path already caches
  `mainKeyRenderSpec` across steady-state draws.
- Python and JVM tests now lock geometry before pixels are drawn, which made
  the restored left-anchored body-layout regression cheap to localize.

### What is still heavier than it should be

- `CalculatorSoftkeyPainter.draw(...)` still builds a new `KeyRenderSpec`,
  label list, and adornment list on each draw from the live snapshot, size,
  and pressed state.
- `CalculatorKeyView` still carries detached `TextView` mirrors and the mirror
  sync bridge even though those views no longer own runtime geometry.
- `C47TextRenderer.buildFittedLabelSpec(...)` fits width first and then re-
  enters `buildLabelSpec(...)`, so fitted labels still pay for a second bounds-
  resolution pass.
- The render-spec lookup API is still string-id-based, which keeps the JSON
  contract easy to inspect but leaves Kotlin-side call sites open to typo-level
  drift.

### Recommended follow-up order

1. Cache softkey render specs outside `draw(...)`.
   Use the current snapshot, size, and pressed state as the invalidation key so
   unchanged softkey frames do not allocate a new spec graph on every draw.
   This is the highest-value lean-up because the May 2026 Android guidance
   still treats `onDraw()` allocation avoidance and unnecessary invalidation
   control as first-line custom-view performance work.
2. Extract the compatibility-mirror bridge from `CalculatorKeyView`.
   Keep the current mirrors behind one small helper so the renderer class owns
   spec resolution and drawing, while the compatibility surface becomes
   explicitly disposable once parity helpers stop reading it.
3. Collapse fitted-text measurement into one resolved metrics pass.
   Add an internal resolved-text data object or helper in `C47TextRenderer` so
   width fit and bounds generation reuse the same measurement result instead of
   repeating `measureText(...)` and font-metrics setup.
4. Replace stringly typed label and adornment lookups with typed slots inside
   Kotlin while preserving stable serialized IDs for contracts.
   That keeps the Python payload readable but makes Kotlin refactors less prone
   to silent key mismatches.
5. Split softkey overlay drawing into its own typed stage only if the class
   continues to grow.
   The shared chrome or label path is already centralized; the next extraction
   should target overlay-specific branching rather than forcing all key
   families into one giant painter.

### Follow-up guardrails

- Do not reopen the native snapshot contract just to optimize draw-time
  allocation; keep the work local to the Android render owners.
- Do not remove the mirror bridge and the softkey draw-time spec builder in the
  same patch series; land one ownership reduction at a time.
- Rerun `scripts/r47_contracts/test_key_label_geometry_contract.py`,
  `DynamicKeypadParityFixtureTest.kt`, `CalculatorKeyViewRenderSpecTest.kt`,
  and `CalculatorSoftkeyPainterCanvasTest.kt` on every follow-up slice before
  widening to the full Android lane.

## Annex B - Follow-up task 1: softkey render-spec cache

### Analysis

- The live post-Iteration-99 main-key path already caches `mainKeyRenderSpec`,
  but `CalculatorSoftkeyPainter.draw(...)` still rebuilt a softkey
  `KeyRenderSpec` graph on every draw.
- That meant unchanged softkey frames still paid for label-list allocation,
  adornment-list allocation, fitted-text measurement, and overlay-spec
  reconstruction inside the draw hot path.
- The smallest safe owner path was to keep cache ownership inside
  `CalculatorSoftkeyPainter` and key invalidation off the same stable inputs
  that already decide the spec: snapshot, font set, width, height, pressed
  state, and draw-surface flag.

### Plan

1. Add a private softkey render-spec cache key and cached-spec slot in
  `CalculatorSoftkeyPainter`.
2. Route `buildRenderSpec(...)` through the cache and move the old builder body
  into a private uncached helper.
3. Add a focused JVM test that proves stable inputs reuse the same spec object
  and a changed pressed state invalidates the cache.
4. Update the maintained renderer and hot-path docs so they describe the live
  softkey owner path rather than the pre-cache draw-time rebuild behavior.

### Conventional commit

```text
perf(android): cache softkey render specs outside draw
```

### Outcome

- `CalculatorSoftkeyPainter` now caches the resolved softkey `KeyRenderSpec`
  outside steady-state draw work and rebuilds it only when snapshot, font set,
  size, pressed state, or draw-surface state changes.
- `CalculatorSoftkeyPainterCanvasTest.kt` now proves identical inputs reuse the
  same cached spec instance while a pressed-state change invalidates the cache.
- Maintained renderer and hot-path docs now describe the live cached softkey
  owner path rather than the old rebuild-on-every-draw behavior.

### Validation

- `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.CalculatorSoftkeyPainterCanvasTest`

  Result: passed.

## Annex C - Follow-up task 2: extract the compatibility-mirror bridge

### Analysis

- `CalculatorKeyView` still owns a detached cluster of compatibility-only
  `TextView` mirrors even though those views no longer participate in runtime
  geometry.
- The mirror-specific work is concentrated in setup, font reset, alpha sync,
  render-spec geometry sync, and the draw suppression guard. Those are bridge
  concerns, not render-spec or painter concerns.
- The safest extraction is therefore to move those detached-view operations into
  one helper while leaving the current text, visibility, and typeface-policy
  call sites readable in `CalculatorKeyView` so the existing contracts stay on
  the true render-owner file.

### Plan

1. Add a small helper that owns the detached main-key mirror `TextView`
   instances plus mirror-only setup and geometry-sync behavior.
2. Delegate `CalculatorKeyView` mirror reset, font reset, alpha sync,
   render-spec geometry sync, and draw suppression through that helper.
3. Add a focused render-spec JVM test that proves the detached mirror bridge
   still tracks the resolved spec bounds.

### Conventional commit

```text
refactor(android): extract main-key mirror bridge helper
```

### Outcome

- Added `MainKeyLabelMirrors.kt` so the detached primary, faceplate, and fourth-
  label `TextView` mirrors now live behind one compatibility helper instead of
  scattering mirror-only setup and geometry sync across `CalculatorKeyView`.
- `CalculatorKeyView` now delegates mirror font reset, alpha sync, geometry
  sync, and mirror-view detection through that helper while keeping the
  render-spec builder and painter ownership local.
- `CalculatorKeyViewRenderSpecTest.kt` now proves the detached mirrors still
  track the resolved render-spec bounds.
- Maintained Android docs now list the helper explicitly so the live owner map
  shows the mirror bridge as a compatibility seam rather than part of the
  renderer core.

### Validation

- `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.CalculatorKeyViewRenderSpecTest`

  Result: passed.

## Annex D - Follow-up task 3: collapse fitted-text measurement to one resolved pass

### Analysis

- `C47TextRenderer.buildFittedLabelSpec(...)` still configured the paint,
  resolved the fitted size, and then re-entered `buildLabelSpec(...)`, which in
  turn re-ran bounds resolution as a second step.
- The geometry and text-policy contract should stay exactly the same, but the
  fitted-label path can reuse one internal resolved-metrics helper so the final
  bounds do not need a second independent measurement pass.
- This is a local text-renderer change; it does not need any layout, snapshot,
  or native-contract changes.

### Plan

1. Add one internal resolved-text metrics helper in `C47TextRenderer`.
2. Route both `buildLabelSpec(...)` and `buildFittedLabelSpec(...)` through that
   helper so fitted labels build the final `LabelSpec` directly instead of
   re-entering a second bounds pass.
3. Extend `C47TextRendererTest.kt` with a focused equivalence check that the
   fitted-label path still matches a direct label-spec build at the resolved
   final size.

### Conventional commit

```text
perf(android): reuse resolved metrics for fitted labels
```

### Outcome

- `C47TextRenderer` now routes both direct and fitted label-spec construction
  through one internal resolved-text metrics helper instead of bouncing fitted
  labels through a second label-spec builder.
- The final fitted-label bounds stay aligned with the pre-refactor behavior,
  because the helper still resolves the final-width measurement exactly at the
  resolved text size.
- `C47TextRendererTest.kt` now proves the fitted-label path matches a direct
  label-spec build at the resolved final size.
- Maintained renderer docs now describe the resolved-metrics helper and the new
  focused text-renderer regression surface.

### Validation

- `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.C47TextRendererTest`

  Result: passed.

## Conventional commit

```text
refactor(android): unify key rendering through shared render specs

Move both main keys and softkeys onto a spec-first draw pipeline backed by a
shared KeyRenderSpec vocabulary and shared painter helpers. Keep detached label
views only as compatibility mirrors, promote the new geometry seam into JVM and
Python contracts, and rebaseline the maintained renderer docs around the render
spec as the first owner to inspect.
```
