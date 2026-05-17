# REPORT-14-LOCKFREE-HOTPATH

## Requested outcome

Document the remaining Android-specific hot-path bug after the landed REPORT-13
redesign, compare it against the desktop simulator behavior, evaluate fix
options with official Android guidance in mind, and leave a lean implementation
plan plus tests that can prove the result.

## Scope and non-goals

- Scope is the residual Android lock-free hot-path problem, not the original
  REPORT-13 throughput redesign.
- Shared-core `NaN` behavior is treated as evidence, not as the primary target
  of this report.
- This report does not recommend reviving the rejected single-step scheduler.
- This report is allowed to challenge the earlier Android-only assumption where
  the safer engineering answer appears to require a tiny shared-core-visible
  stop accessor.

## Executive summary

The remaining Android bug is not accurately described as only queue-bound stop
starvation.

There are two Android-owned failure planes:

- control plane: Android live key input is still routed through
  `NativeCoreRuntime.offerTask(...)`, so a busy core-owner thread can starve a
  queued `R/S`
- observation plane: the UI thread still builds keypad snapshots through JNI
  methods that block on `screenMutex`, so the app can foreground-ANR before any
  touch-driven stop gesture has a chance to help

The desktop simulator remains a strong control because it does not use the same
Android queue boundary. In `PC_BUILD`, `btnPressed(...)` can set
`programRunStop = PGM_WAITING` directly while a program is already running, and
some long-running paths also poll `exitKeyWaiting()` through `currentKeyCode`.
That means the simulator can still stop in cases where Android cannot, without
proving that every shared-core loop yields equally.

The best lean and robust design is a combined fix in two ordered steps:

1. replace split blocking keypad polling with one coherent non-blocking keypad
   snapshot contract that reuses the last accepted snapshot on busy
2. add a lock-free dedicated stop-request seam that does not queue behind the
   Android core-owner thread and does not take `screenMutex`

If that combined Android fix lands and a given workload still cannot be
interrupted, the remaining defect is no longer "Android handwaving". The
remaining defect is that the active shared-core path does not observe
`programRunStop` often enough.

## Implementation status (2026-05-17)

Phases 1 and 2 are now landed in the Android-owned bridge and runtime path.

Landed code shape:

- `NativeDisplayRefreshLoop` now reads `getKeypadSnapshotGeneration()` and a
  cached `NativeKeypadSnapshotStore` instead of split per-frame keypad JNI
  polling
- `copyKeypadSnapshotNative(...)` now assembles one coherent keypad snapshot
  under one `pthread_mutex_trylock(&screenMutex)` critical section and reuses
  the last accepted Kotlin snapshot on busy
- USER mode composition now happens inside that one native snapshot export
  rather than through multiple JNI reads on the UI thread
- `MainActivity.dispatchLiveKey(...)` now routes positive live `R/S` and
  `EXIT` presses through `requestStopProgramNative()` before queue fallback
- `requestStopProgramNative()` publishes stop intent without taking
  `screenMutex`, using the existing upstream `fnStopProgram()` entry point

Focused verification completed in this workspace:

- `cd android && ./gradlew :app:testDebugUnitTest --tests io.github.ppigazzini.r47zen.NativeDisplayRefreshLoopTest --tests io.github.ppigazzini.r47zen.NativeCoreRuntimeTest --tests io.github.ppigazzini.r47zen.ReplicaOverlayControllerLabelModeTest --tests io.github.ppigazzini.r47zen.DynamicKeypadParityFixtureTest`
- `cd android && ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./gradlew lint`
- `ANDROID_HOME=/home/usr00/.android/sdk ANDROID_SDK_ROOT=/home/usr00/.android/sdk ./scripts/android/build_android.sh`

Current honest follow-up:

- the connected-device instrumentation seam described in Phase 3 is still not
  landed
- if `MANSLV2.p47` still ignores stop after this Android fix, the remaining
  bug should now be classified as shared-core stop observation rather than as
  Android queue starvation or UI-thread keypad export blocking

## Problem statement

Observed behavior:

- `MANSLV2.p47` now runs smoothly for a long time on Android with the landed
  hot-path redesign
- after enough iterations, some values become `NaN` and the program keeps
  running
- on the desktop simulator, that non-terminating state can still be stopped
- on Android, the app can become unresponsive enough that the user sees wait or
  kill behavior and touch no longer helps

The source-backed Android claim in this report is not the mathematical source
of the `NaN`. The Android claim is that the shell currently allows a shared-core
non-yielding run to block both the stop path and the UI observer path.

## Source-backed current Android path

### Android control path

- touch, PiP, and physical-keyboard keys all route through
  `NativeCoreRuntime.offerTask(...)`
- the live hot key path remains `MainActivity.sendKey(int)` through
  `jni_input.c`
- `fnRunProgram()` enters `runProgram(false, INVALID_VARIABLE)` synchronously
- `fnStopProgram()` sets `programRunStop = PGM_WAITING`
- the Android bridge does not currently expose a dedicated stop publisher that
  bypasses the core-owner queue

Implication:

- if the core-owner thread is stuck inside a non-yielding run, queued `R/S`
  cannot overtake it

### Android UI observer path

- `NativeDisplayRefreshLoop.refreshFrame(...)` calls
  `getKeypadMetaNative(...)` on the UI thread every frame
- when labels refresh, the loop delegates to
  `ReplicaOverlayController.currentKeypadSnapshot(...)`
- `currentKeypadSnapshot(...)` calls `snapshotForMode(...)`
- `snapshotForMode(...)` calls `getKeypadLabelsNative(...)`
- in `USER` mode, `currentKeypadSnapshot(...)` can trigger two native snapshot
  reads in one refresh by composing `USER` and `OFF`
- in `jni_display.c`, both `getKeypadMetaNative(...)` and
  `getKeypadLabelsNative(...)` still use blocking
  `pthread_mutex_lock(&screenMutex)`

Implication:

- even if packed LCD export is already non-blocking, keypad export can still
  block the main thread on the same native lock

### Shared-core stop observation

- `runProgram()` checks `programRunStop` at its step boundary
- many long-running helper loops in staged core also check `programRunStop ==
  PGM_WAITING` or `exitKeyWaiting()`
- there is no guarantee that every pathological inner loop observes the stop
  flag often enough

Implication:

- a direct Android stop publisher can improve control latency, but it is not a
  preemptive interrupt

## Desktop simulator comparison

The desktop simulator does not solve the Android problem by magic. It solves a
different problem because its wiring is different.

### What the simulator does differently

- in `PC_BUILD`, `keyboard.c::btnPressed(...)` sets `currentKeyCode` directly
  and, when a program is already running, sets `programRunStop = PGM_WAITING`
  immediately for `R/S` or `EXIT1`
- `c47Extensions/addons.c::anyKeyWaiting()` and `exitKeyWaiting()` inspect
  `currentKeyCode` directly in `PC_BUILD`
- `programming/input.c` pumps `g_main_context_iteration(...)` and
  `gtk_main_iteration()` in `PC_BUILD` wait and pause paths
- `android_runtime.c` has Android mock GTK or GLib functions only for the
  Android compatibility layer; it is not the real simulator GUI loop

### Why that matters

- desktop input does not have the Android-specific UI-thread to queue to
  core-owner indirection
- desktop can publish stop intent directly into shared-core state from the key
  callback path
- some desktop loops have an additional polled EXIT fallback via
  `currentKeyCode`

### What the simulator does not prove

- it does not prove that every shared-core loop yields
- it does not prove that Android can safely copy the desktop path, because
  Android also has a main-thread ANR constraint that desktop GTK does not share

Conclusion:

- the simulator is evidence against a pure math-only explanation
- the simulator is not evidence that a stop seam alone fixes Android

## Official Android references and what they imply

Primary Android references used for this report:

- ANRs: `https://developer.android.com/topic/performance/vitals/anr`
- Keep your app responsive: `https://developer.android.com/training/articles/perf-anr`
- Threading guidance: `https://developer.android.com/topic/performance/threads`
- JNI tips: `https://developer.android.com/ndk/guides/jni-tips`

Key Android guidance relevant here:

- foreground ANRs are triggered when input dispatch cannot be serviced within
  about 5 seconds
- main-thread lock contention on a worker-owned resource is a documented ANR
  cause
- the main thread should stay unblocked and should do as little non-UI work as
  possible
- JNI guidance says to minimize marshalling, minimize how many threads touch
  JNI, and avoid awkward cross-language async designs when possible
- JNI guidance also recommends using region-style copies or similarly simple
  copy contracts where that fits the data path

Implications for this repo:

- the current split keypad JNI polling is a bad fit for Android guidance
- building one logical keypad snapshot through two or more blocking JNI reads on
  the main thread is exactly the kind of design that makes lock contention hard
  to control
- a lock-free stop seam alone is insufficient if the main looper can already be
  blocked in keypad export

## Option analysis

### Option A: minimal split-API trylock patch

Change `getKeypadMetaNative(...)` and `getKeypadLabelsNative(...)` from blocking
`pthread_mutex_lock` to `trylock`, then keep the last Kotlin-side data when a
call fails.

Pros:

- smallest patch by lines changed
- likely enough to remove the worst ANR mode quickly
- keeps most existing Kotlin plumbing intact

Cons:

- the current API shape is still split across multiple JNI calls for one logical
  snapshot
- Kotlin cannot distinguish "busy" from "fresh but unchanged" without adding
  extra signaling
- `USER` mode can still require more than one native labels read per refresh
- partial success can mix different native moments into one `KeypadSnapshot`

Devil's advocate:

- this may be enough in practice if the remaining risk of mixed snapshots is
  visually harmless
- but it is the least honest long-term contract and the hardest one to reason
  about in tests

### Option B: coherent whole-snapshot try-copy transport

Replace split keypad polling with one non-blocking whole-snapshot copy path,
generation-gated like packed LCD.

Recommended shape:

- `getKeypadSnapshotGeneration(...)`
- `copyKeypadSnapshot(...): Boolean`
- caller-owned reusable primitive buffers or a direct buffer, not `String[]`
  as the hot transport

Pros:

- matches Android guidance better than the split API
- one logical snapshot is copied under one coherent native critical section
- busy can be represented explicitly as busy
- Kotlin can reuse the last accepted snapshot exactly like packed LCD reuse
- region-style primitive copies are easier to keep allocation-light on the UI
  thread

Cons:

- requires a new transport contract
- `USER` mode composition still has to be handled carefully

Devil's advocate:

- more code than Option A
- if labels remain string-heavy internally, the native side still does some
  formatting work

Judgement:

- best balance of lean plus robust if implemented with fixed-width primitive
  buffers and clear busy semantics

### Option C: native-owned cached keypad snapshot

Move keypad snapshot assembly fully off the UI thread and expose a completed
cached snapshot behind a dedicated snapshot lock or double-buffer handoff.

Pros:

- strongest decoupling from `screenMutex`
- UI thread no longer depends on the live calculator lock for keypad export
- makes repeated per-frame reads cheap after cache publication

Cons:

- more native ownership and invalidation complexity
- more state to keep in sync
- easier to over-engineer before profiling proves the extra complexity is worth
  it

Devil's advocate:

- this may become the right answer if keypad export remains hot after the
  coherent try-copy contract lands
- but it is not the leanest first repair

### Option D: dedicated stop seam only

Add `requestStopProgramNative()` or equivalent and use it for the Android stop
case instead of queueing `R/S` through `NativeCoreRuntime`.

Pros:

- directly addresses queue-bound control latency
- preserves coarse native-owned execution

Cons:

- does not help if the main thread is already blocked in keypad export
- if implemented as a raw cross-thread write to plain `uint8_t
  programRunStop`, it is a weaker concurrency contract than a tiny accessor or
  atomic-aware wrapper
- it still cannot stop loops that never observe the stop flag

Devil's advocate:

- if the ANR theory were wrong and only the control plane were broken, this
  would be the smallest useful fix
- the current evidence does not support that narrower theory anymore

### Option E: shared-core yield or scheduler changes

Patch shared-core run paths to yield more often or revive Android-side sliced
execution.

Pros:

- can make more loops interruptible if they currently do not observe stop often
  enough

Cons:

- reopens the same semantic and throughput risk that REPORT-13 already rejected
- touches shared execution behavior for an Android-owned problem

Devil's advocate:

- if one or more remaining loops stay uninterruptible after the Android fixes,
  then a tiny shared-core stop-observation improvement may still be necessary
- that is still not an argument for bringing back `runProgram(true, ...)`

## Devil's-advocate discussion

### Objection 1: a special `R/S` seam is enough because the simulator can stop

Counterpoint:

- the simulator does not use the Android queue boundary
- the simulator also does not prove Android main-thread safety
- Android ANR guidance makes the UI-thread lock problem first-order, not
  optional

### Objection 2: a whole-snapshot contract is over-engineering; just use trylock

Counterpoint:

- split APIs create mixed-snapshot edge cases and make stale reuse ambiguous
- the current `USER` composition path already shows that one logical snapshot is
  bigger than one JNI method call
- the leanest robust code is not the fewest edited lines; it is the smallest
  contract that stays coherent under failure

### Objection 3: a shared-core-visible stop accessor violates the Android-owned
boundary

Counterpoint:

- this is the strongest criticism of the recommended stop seam
- if the repo absolutely refuses any shared-core-visible stop accessor, a
  documented Android-only direct write is still possible
- however, if the goal is the most robust code rather than the purest ownership
  story, a single tiny accessor or atomic-aware wrapper is the cleaner
  concurrency contract

### Objection 4: stale keypad snapshots are visually wrong

Counterpoint:

- stale for one or a few frames is acceptable on Android
- ANR and dead input are not acceptable
- packed LCD already uses the same principle: keep the last good state until a
  new coherent copy succeeds

## Final recommendation

Implement a combined two-step repair.

### Recommendation step 1

Replace split blocking keypad polling with one coherent non-blocking whole-
snapshot contract.

Preferred properties:

- one generation per logical keypad snapshot
- one non-blocking copy method with explicit success or busy result
- reusable primitive buffers or direct buffers, not hot-path `String[]`
- Kotlin reuses the last accepted `KeypadSnapshot` on busy
- `USER`-mode composition is either satisfied in one native call or made
  coherent at whole-snapshot scope

### Recommendation step 2

Add a dedicated stop-request seam that does not route through
`NativeCoreRuntime` and does not take `screenMutex`.

Preferred engineering choice:

- a tiny shared-core-visible accessor or atomic-aware wrapper around the stop
  publication

Fallback if shared-core-visible change is refused:

- a narrow Android-only direct stop publication with the concurrency compromise
  documented clearly

### Recommendation step 3

Only after those Android fixes land, evaluate whether any remaining long loops
still need shared-core stop-observation improvements.

This ordering matches the Android references better than a stop-only or
scheduler-first approach.

## Detailed implementation plan

### Phase 0: observability first

- capture one Android bugreport or ANR trace for the current repro
- add clear logging or tracing around keypad snapshot generation, busy returns,
  and stop publication
- keep `simpleperf` and Perfetto available for follow-up, but do not block the
  first repair on them

### Phase 1: coherent non-blocking keypad snapshot export

Native side:

- add a keypad snapshot generation counter distinct from packed LCD generation
- add one coherent copy routine that assembles a full snapshot under
  `screenMutex`
- use `pthread_mutex_trylock(&screenMutex)`
- if lock acquisition fails, return busy immediately without partial data
- copy into local native buffers first, then marshal into Java buffers after
  releasing the lock when practical
- avoid hot-path `String[]` allocation if possible; prefer fixed-width flattened
  label storage plus region copies or a direct buffer

Kotlin side:

- replace split `getKeypadMetaNative(...)` plus `getKeypadLabelsNative(...)`
  reads in `NativeDisplayRefreshLoop`
- keep one last accepted `KeypadSnapshot`
- only update that snapshot when the non-blocking copy succeeds
- on busy, reuse the last accepted snapshot and skip synthetic empty-state
  refresh

`USER` mode handling:

- either export both required mode views in one coherent native call
- or move the `USER` composition boundary so one accepted snapshot is still one
  logical snapshot

### Phase 2: dedicated stop publication

Native side:

- add `requestStopProgramNative()` or equivalent
- do not take `screenMutex`
- do not call `btnPressed()` or `btnReleased()`
- publish stop intent only

Kotlin side:

- invoke the seam only for the stop case while a program is already running
- retain existing queued key routing for normal start and normal keypad usage

Shared-core side if allowed:

- prefer a tiny accessor or atomic-aware wrapper for stop publication instead of
  a raw external write to `programRunStop`

### Phase 3: focused tests

JVM tests:

- lock that unchanged keypad generation skips copies
- lock that busy snapshot copy reuses the last accepted snapshot
- lock that no synthetic empty keypad snapshot is emitted on busy
- lock `USER`-mode composition behavior under the new whole-snapshot contract

Instrumentation tests for responsiveness:

- add an instrumentation-only seam that can force the keypad snapshot path to
  report busy or hold the export lock briefly without corrupting runtime state
- start a long-running workload
- post a real main-looper runnable after the workload begins
- assert that it runs within a bounded timeout while the busy snapshot path is
  active
- assert that the UI still shows the last accepted snapshot instead of blocking

Instrumentation tests for interruptibility:

- use a long-running path that still observes `programRunStop`
- assert that the run starts through the live runtime path
- assert that the main thread stays responsive enough to publish stop intent
- assert that the dedicated stop seam changes stop state without queueing onto
  `NativeCoreRuntime`
- assert that the run exits and cleanup happens without reviving the rejected
  scheduler

Test-surface warning:

- the current `ProgramLoadTestBridge.beginMainActivityKeySequenceNative()` is
  not sufficient proof of UI-thread responsiveness because it injects the key
  from a native worker thread and calls the native `sendKey` entrypoint
  directly

### Phase 4: post-fix review

- if Android remains responsive and stop publication fires, but the run still
  will not stop, classify the remaining bug as a shared-core stop-observation
  gap
- only then decide whether a tiny shared-core stop-observation improvement is
  justified

## Exit criteria

The fix is credible only if all of the following are true:

- the UI thread no longer blocks behind keypad snapshot export
- keypad snapshot export remains coherent under busy or retry conditions
- Android can publish stop intent without queueing onto the busy core-owner
  thread
- a long-running path that still observes `programRunStop` can be stopped
  without reviving `runProgram(true, ...)`
- if some path still cannot be interrupted after that, the remaining limitation
  is documented honestly as a shared-core stop-observation issue rather than as
  an unresolved Android mystery

## Bottom line

The best lean and robust code is not a stop-only seam and not a blind `trylock`
patch on the existing split JNI API.

The best lean and robust code is:

- one coherent non-blocking keypad snapshot contract
- one lock-free dedicated stop publisher
- one test story that proves both UI-thread responsiveness and real
  interruptibility

That is the shortest path that matches the current source evidence, the desktop
simulator comparison, and the official Android guidance.
