# ITERATION-102

## Outcome

Land the Android-owned hot-path repair from REPORT-14 without reopening the
rejected sliced scheduler. The shipped change must remove UI-thread keypad
export blocking, stop queue-bound `R/S` starvation on the live touchscreen path,
and keep the repo's JNI, staging, and maintainer docs aligned.

## Scope

- Android-owned Kotlin runtime and overlay refresh path
- Android-owned JNI display and input bridge
- Android-owned direct stop publication for live stop keys
- maintainer docs that describe the JNI hot path and verification surfaces

## Constraints

- Preserve the landed REPORT-13 core-thread and display baseline
- Keep canonical root `src/` sources authoritative over staged Android copies
- Keep Kotlin externs, JNI registration, and native implementations aligned
- Do not revive `runProgram(true, ...)` or any Android-only sliced executor
- Keep docs truthful about what was and was not verified in this workspace

## Non-goals

- Do not diagnose the mathematical source of the `MANSLV2.p47` `NaN` loop
- Do not claim that Android can preempt a shared-core loop that never observes
  `programRunStop`
- Do not widen the stop seam to normal start or normal keypad routing

## Baseline

Before this iteration:

- `NativeDisplayRefreshLoop` polled split keypad JNI APIs from the main thread
- `getKeypadMetaNative(...)` and `getKeypadLabelsNative(...)` both blocked on
  `screenMutex`
- USER mode could assemble one logical keypad scene through more than one JNI
  read
- live touchscreen `R/S` still queued through `NativeCoreRuntime.offerTask(...)`

That left two Android-owned failure planes:

- the main looper could block behind keypad export lock contention
- queued `R/S` could starve behind a busy core-owner thread

## Options

### Option 1: split trylock retrofit

Change the existing split meta and label JNI calls to `pthread_mutex_trylock`
and keep the last Kotlin-side values when a call fails.

Pros:

- smallest code delta
- likely reduces the worst ANR mode quickly

Cons:

- still mixes one logical keypad scene across multiple JNI calls
- USER mode still needs multiple native reads
- Kotlin cannot distinguish stale, busy, and unchanged data cleanly

### Option 2: whole-snapshot try-copy plus direct stop publication

Add one keypad snapshot generation, one non-blocking whole-snapshot copy API,
one Kotlin snapshot cache, and one direct stop publisher for live `R/S` or
`EXIT` presses.

Pros:

- matches Android ANR and JNI guidance better than the split API
- keeps one logical keypad scene under one native lock window
- lets the UI reuse the last accepted snapshot on busy without inventing empty
  scenes
- removes queue-bound stop starvation for the live touchscreen stop-key path

Cons:

- larger change than a split trylock retrofit
- still leaves a truthful shared-core follow-up if some loop never observes the
  stop flag

### Option 3: native-owned cached snapshot thread plus scheduler changes

Move keypad export fully off the UI thread and reopen Android-owned sliced
execution.

Pros:

- strongest decoupling from the live lock

Cons:

- reopens the REPORT-13 risk surface
- adds more ownership and invalidation complexity than the evidence requires

## Recommendation

Ship Option 2.

Role review:

- Software architect: it keeps the fix inside Android-owned surfaces plus one
  existing upstream stop entry point.
- Android platform engineer: it removes the known main-thread lock-contention
  hazard from keypad export.
- NDK and JNI engineer: it replaces split polling with one explicit copy
  contract and keeps registration explicit.
- Core and embedded engineer: it avoids scheduler churn and limits shared-core
  dependence to the existing stop-publication path only.
- Build and release engineer: it preserves the current staging and build flow.
- Technical writer: it gives maintainer docs one coherent post-fix contract.

## Official references used

- Android ANR guidance: foreground input-dispatch timeout and main-thread lock
  contention remain the relevant failure model. The current page was last
  updated 2026-03-05.
- Android threading guidance: the main thread stays a work queue that must not
  absorb long or numerous tasks. The current page was last updated 2024-01-03.
- JNI tips: minimize marshalling, avoid awkward cross-language async designs,
  and prefer region-style copies when a copy contract is sufficient. The current
  page was last updated 2026-03-06.

## Implementation

- Added `NativeKeypadSnapshotStore` so Kotlin owns one cached accepted snapshot
  per main-key mode and retries busy generations until a coherent copy lands.
- Replaced split refresh-loop keypad polling with
  `getKeypadSnapshotGeneration()` and `copyKeypadSnapshotNative(...)`.
- Moved USER-mode composition into the native whole-snapshot export so one
  logical scene is assembled under one `screenMutex` hold.
- Added `requestStopProgramNative()` and routed touchscreen `R/S` and `EXIT`
  presses through `MainActivity.dispatchLiveKey(...)` before queue fallback.
- Routed Android stop publication through the existing upstream
  `fnStopProgram()` entry point so Android does not need to take `screenMutex`
  or call the full queued key path.
- Updated maintainer docs and focused JVM tests to describe and lock the landed
  contract.

## Verification

Focused JVM tests:

- `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.NativeDisplayRefreshLoopTest --tests io.github.ppigazzini.r47zen.NativeCoreRuntimeTest --tests io.github.ppigazzini.r47zen.ReplicaOverlayControllerLabelModeTest --tests io.github.ppigazzini.r47zen.DynamicKeypadParityFixtureTest`

Mandatory Android lint:

- `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew lint`

Full Android staging and build flow:

- `ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./scripts/android/build_android.sh`

Results:

- focused JVM lane passed
- lint passed
- full Android build flow passed and produced the debug APK

Not run here:

- connected-device instrumentation for the new stop seam and forced-busy keypad
  path
- manual `MANSLV2.p47` device repro after the landing

## Remaining risks and follow-up

- A shared-core loop that never observes `programRunStop` still cannot be
  preempted by Android.
- There is still no connected-device automated seam that proves main-looper
  responsiveness and direct stop interruptibility under a live long-running run.
- If `MANSLV2.p47` still resists stop after this landing, classify the
  remaining defect as shared-core stop observation, not as Android queue or
  ANR mystery.

## Squash-ready commit

```text
fix: unblock Android keypad export and stop publication

Replace split blocking keypad JNI polling with a single non-blocking
whole-snapshot copy path and a cached Kotlin snapshot store. Route
live R/S presses through a direct stop publisher so stop intent no
longer queues behind the busy core thread. Update maintainer docs and
focused JVM contracts, then verify lint and the staged Android build.
```

## Annex - EXIT stop parity

### Analysis

- The desktop run loop in `lblGtoXeq.c` stops a running program when the polled
  key is `36` or `33`, which is the simulator's `R/S` and `EXIT` parity rule
  during intense computation.
- Android already had a direct stop publisher in
  `MainActivity.dispatchLiveKey(...)`, but it only fast-pathed key code `36`.
  Live `EXIT` still fell back to queued `sendKey(33)` work.
- `requestStopProgramNative()` already returns `false` unless native state is
  `PGM_RUNNING` or `PGM_PAUSED`, so extending the direct path to `EXIT` keeps
  normal `EXIT` behavior outside long-running program execution.

### Propositions

1. Keep the Android direct stop seam `R/S`-only.
2. Route live `EXIT` through the existing direct stop publisher too.
3. Add a second JNI export dedicated only to `EXIT`.

### Recommendation

Use proposition `2`.

- It matches the desktop simulator's run-loop stop rule.
- It stays Android-owned and reuses the existing JNI stop publisher.
- It preserves normal queued `EXIT` semantics when no program is currently
  running or paused.

### Planning

1. Add one Kotlin helper that names the live direct-stop key set.
2. Make `MainActivity.dispatchLiveKey(...)` use that helper before queue
   fallback.
3. Add a focused JVM test that locks `R/S` and `EXIT` parity.
4. Update the hot-path maintainer docs and this iteration note.
5. Validate with the focused JVM test and `assembleDebug`.

### Conventional commit

```text
fix(android): make live EXIT stop long-running programs

Mirror the desktop run loop, which treats both R/S and EXIT as
immediate stop keys while a program is already running. Route live
EXIT presses through the existing Android direct-stop JNI path, add a
focused JVM policy test, and update the hot-path maintainer docs.
```
