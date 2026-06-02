# Upstream Interface Surfaces

This page maps the exact Android-owned calls that cross into the staged
upstream core and the exact native callbacks that cross back into the activity.

Read `00-project-and-upstream.md` first. This page assumes the project and
ownership boundary is already clear.

Read this page when a task touches `MainActivity` external methods, JNI
registration, packed LCD export, lifecycle save or load entry points,
instrumentation-only bridge seams, keypad-scene export, SAF-backed file
requests, or pause and wait behavior in the staged `PC_BUILD` core. Read
`80-tests-and-contracts.md` for the focused bridge, READP, and storage
verification surfaces.

## Interface Inventory

| Surface | Android side | Native bridge | Shared-core side | Sensitive detail |
| --- | --- | --- | --- | --- |
| runtime boot and attach | `NativeCoreRuntime.attach()` calls `nativePreInit()`, `initNative()`, `tick()`, and `updateNativeActivityRef()` | `jni_registration.c` plus `jni_lifecycle.c` | `setupUI()`, `doFnReset()`, `restoreCalc()`, `fnTimerConfig(...)` | `tick()` only runs when `pthread_mutex_trylock(&screenMutex)` succeeds, returns the next wake delay after due timer or LCD work, and reattach stays display-passive |
| lifecycle save, load, and explicit refresh | `saveStateNative()`, `loadStateNative()`, `forceRefreshNative()` | `jni_lifecycle.c` | `saveCalc()`, `restoreCalc()`, `refreshScreen(190)`, `refreshLcd(NULL)`, `lcd_refresh()` | `saveStateNative()` must stay display-passive for background save; redraw belongs only to real state loads or explicit refresh owners |
| direct input dispatch | `sendKey()`, `sendSimKeyNative()`, `sendSimMenuNative()`, `sendSimFuncNative()`, `requestStopProgramNative()` | `jni_input.c` | `btnPressed(...)`, `btnReleased(...)`, `showSoftmenu(...)`, `runFunction(...)`, `fnStopProgram(...)` | `requestStopProgramNative()` publishes stop plus a pending stop-refresh request without taking `screenMutex`; `tick()` and `yieldToAndroidWithMs()` later consume that request under `screenMutex`, while the remaining input paths still serialize on `screenMutex`, and some skip while `isCoreBlockingForIo` is true |
| LCD and keypad snapshot export | `getPackedDisplayGeneration()`, `getPackedDisplayBuffer()`, `setLcdColors()`, `getKeypadSnapshotGeneration()`, `copyKeypadSnapshotNative()`, `getKeypadMetaNative()`, `getKeypadLabelsNative()` | `jni_display.c` plus `hal/lcd.c` | `packedDisplayGeneration`, packed LCD rows, keypad snapshot generation, compatibility `screenData`, visible key tables, label resolvers | the generation checks short-circuit unchanged LCD and keypad work, `getPackedDisplayBuffer()` and `copyKeypadSnapshotNative()` both exit early when the lock is busy, and the legacy split keypad getters remain compatibility surfaces rather than the hot UI path |
| instrumentation-only runtime probes | `ProgramLoadTestBridge.forceRefresh()`, `saveBackgroundStateForTest()`, `captureDisplayHash()`, `beginSimFunction()`, `snapshotState()` | `jni_program_load_test.c` | `r47_force_refresh()`, `r47_save_background_state_locked()`, packed LCD snapshot state, READP or RUN workers | lifecycle snapshot hashes must ignore packed-row transport metadata so assertions compare visible LCD bytes only |
| native to activity callbacks | `requestFile()`, `playTone()`, `stopTone()`, `processCoreTasks()` | `updateNativeActivityRef()` refreshes the global activity reference and caches `jmethodID`s; `processCoreTasksNative()` calls back into Java | lets long native waits service Android work | cached method IDs and Kotlin method signatures must stay aligned, and reattach must not redraw the LCD |
| storage and yield boundary | `StorageAccessCoordinator` returns detached file descriptors through `onFileSelectedNative()` or `onFileCancelledNative()` | `jni_storage.c` plus `android_runtime.c` | `ioFileOpen(...)`, long-running waits, timer refresh | both paths release and later reacquire the recursive `screenMutex` |

## Bridge Flow

```mermaid
flowchart LR
  A[MainActivity and helper controllers]
  B[NativeCoreRuntime queue]
  C[registered JNI entrypoints]
  D[screenMutex critical sections]
  E[staged upstream core]
  F[cached activity callbacks]

  A --> B --> C --> D --> E
  E --> C --> F --> A
```

## Boot And Activity Reference Contract

- `register_main_activity_natives()` in `jni_registration.c` registers the full
  `MainActivity` external-method surface. Signature drift is a hard failure, so
  change Kotlin declarations, `JNINativeMethod` entries, and native
  implementations in the same edit.
- `NativeCoreRuntime.startOrAttachCoreThread()` initializes native state only
  once. The first attach calls `nativePreInit(...)` and `initNative(...)`; a
  later attach only refreshes the activity reference through
  `updateNativeActivityRef()` and keeps reattach display-passive.
- `r47_native_preinit_path(...)` sets the Android base path used by
  `hal/io.c` and installs the GMP allocator hooks before the shared core starts.
- `r47_init_runtime(...)` sets the current slot, calls `setupUI()`, initializes
  LCD buffers, resets and restores calculator state, sets both
  `nextScreenRefresh` and `nextTimerRefresh`, and registers the native timer
  callbacks that the staged core expects.
- `Java_com_example_r47_MainActivity_tick(...)` is the steady-state entry point
  from the Kotlin core thread. It uses `pthread_mutex_trylock(&screenMutex)`,
  advances timers every 5 ms, refreshes the LCD every 100 ms when the lock is
  available, and returns the next wake delay in milliseconds through
  `r47_next_tick_delay_ms(sys_current_ms())`.

## Lifecycle Save, Load, And Explicit Refresh Contract

- `MainActivity.onPause()` routes background persistence through
  `NativeCoreRuntime.saveStateOnPause(...)`, which posts `saveStateNative()` to
  the core thread and waits for completion.
- `saveStateNative()` locks `screenMutex` and delegates to
  `r47_save_background_state_locked()`, which now performs only `saveCalc()`.
  That path must stay display-passive for a normal background save or
  Settings-entry transition.
- `MainActivity.onResume()` no longer requests `forceRefreshNative()` for a
  normal Settings return. The existing display loop and overlay resume hook are
  sufficient to preserve the current snapshot.
- `loadStateNative()` remains a redraw owner because it reconstructs calculator
  state through `restoreCalc()` and then refreshes the LCD.
- `forceRefreshNative()` remains an explicit redraw seam for runtime init and
  test-controlled refresh owners. Do not reintroduce it into passive lifecycle
  callbacks that do not replace calculator state.

## Input Dispatch Contract

- Touch, PiP taps, physical-keyboard actions, and some display actions all land
  in `MainActivity`, then either queue work through
  `NativeCoreRuntime.offerTask(...)` or call one of the direct JNI input entry
  points.
- `sendKey(int)` is the main numeric key path. It maps key codes `1..37` onto
  `btnPressed(...)` or `btnReleased(...)` and key codes `38..43` onto the
  dedicated function-key press and release handlers.
- `MainActivity.dispatchLiveKey(...)` is the live touch and PiP tap seam. It
  now routes `R/S` and `EXIT` through `requestStopProgramNative()` before queue
  fallback, so live stop publication no longer waits on the core-owner queue.
  When native code reports that no program is running or paused, the same key
  falls back to the normal queued `sendKey(...)` path and keeps its standard
  calculator meaning.
- That direct seam is intentionally two-phase. `requestStopProgramNative()`
  publishes stop intent immediately and also marks a pending stop-refresh
  request; `tick()` or `yieldToAndroidWithMs()` later consume that request
  under `screenMutex` so the first post-stop LCD matches an explicit refresh
  without moving redraw work onto the UI thread.
- Keep that publisher lock-free. The `MANSLV2` Android fixture can still be
  executing inside the asynchronous `R/S` key worker while that worker owns
  `screenMutex`; if `requestStopProgramNative()` waits on the mutex, the
  bounded-stop regression turns back into an unbounded hang.
- That queue-bound control path is not the whole Android ANR story. Touch
  dispatch itself stays lightweight; the stronger current suspect is the
  main-thread snapshot export path in `NativeDisplayRefreshLoop`.
- `sendSimKeyNative(String, boolean, boolean)` is the string-key path used by
  the physical keyboard mapper and display actions. It is a cold simulation or
  control surface, not the hot keypad path. It bails out when
  `isCoreBlockingForIo` is set so Android does not inject keypad work while the
  core is suspended in a SAF file request.
- `sendSimMenuNative(int)` calls `showSoftmenu(...)` and then forces a screen
  refresh. `sendSimFuncNative(int)` calls `runFunction(...)` directly.
- All of these paths lock `screenMutex`, so they must stay short and must not
  add Android-side blocking work inside the native critical section.

## Display And Keypad Snapshot Export

- `NativeDisplayRefreshLoop.doFrame(...)` is the only continuous poller on the
  Android side. It reads `getPackedDisplayGeneration()` first and only attempts
  a packed-LCD copy when the generation changed; keypad metadata and labels are
  still polled while the app is active.
- `getPackedDisplayBuffer(...)` copies the packed LCD snapshot only when
  `lcdBufferDirty` is true and returns `true` only after a successful copy. It
  uses `pthread_mutex_trylock`, so a busy native section simply skips one frame
  instead of blocking the UI thread.
- `getKeypadSnapshotGeneration()` exposes the native keypad snapshot generation
  used by the UI-side refresh loop.
- `copyKeypadSnapshotNative(mainKeyDynamicMode, metaBuffer, labelBuffer)` is
  the Android hot-path keypad export. It fills one fixed `KEYPAD_META_LENGTH`
  integer array plus one label array under a single
  `pthread_mutex_trylock(&screenMutex)` critical section and returns `false`
  when the native side is busy.
- `NativeKeypadSnapshotStore` owns the reusable Kotlin-side buffers and caches
  the last accepted snapshot per main-key mode. `NativeDisplayRefreshLoop`
  checks generation first and reuses that cached snapshot when the native copy
  path reports a busy lock.
- USER-mode composition now happens inside `copyKeypadSnapshotNative(...)`, so
  the UI no longer assembles one logical keypad scene through multiple JNI
  reads.
- After a successful copy, the JNI export clears the packed-row dirty flag in
  each copied row. That flag is transport bookkeeping, not part of the visible
  LCD contract.
- `screenData` remains allocated only as a compatibility framebuffer for
  compiled upstream `PC_BUILD` helpers such as screenshot and menu-export
  paths. The Android UI no longer consumes it directly.
- `setLcdColors(...)` marks every native LCD row dirty for future exports while
  `ReplicaOverlay` immediately recolors the cached packed snapshot on the UI
  side.
- the legacy `getKeypadMetaNative(mainKeyDynamicMode)` and
  `getKeypadLabelsNative(mainKeyDynamicMode)` exports remain bridge
  compatibility surfaces and test helpers, but the live Android frame loop no
  longer depends on their split blocking semantics.
- the legacy `r47_get_keypad_meta(..., bool isDynamic)` and
  `r47_get_keypad_labels(..., bool isDynamic)` functions remain the
  bool-based fixture-export contract used by repo tooling and keep the older
  semantics.
- Kotlin converts the copied buffers into `KeypadSnapshot`, and the renderer
  uses named fields from that model instead of indexing raw native arrays
  again. `ReplicaOverlayController` then applies any `virtuoso` keypad-label
  composition plus the softkey `graphic` or `off` mask before the snapshot
  reaches the live renderer.

## Instrumentation-Only Bridge Contract

- `ProgramLoadTestBridge` is the Android-owned instrumentation seam for READP
  program execution coverage, direct-stop publication, and passive lifecycle
  LCD preservation.
- `beginSimFunction(...)`, `snapshotState()`, `requestStopProgram()`, and the
  file-descriptor override helpers drive the same READP or RUN worker paths
  used by `ProgramFixtureInstrumentedTest`.
- `saveBackgroundStateForTest()` routes directly to
  `r47_save_background_state_locked()` so lifecycle tests can assert that the
  pause-side save path is display-passive.
- `captureDisplayHash()` hashes only visible packed LCD bytes. It intentionally
  ignores the row-dirty transport flag that `getPackedDisplayBuffer(...)`
  clears after each successful UI poll.
- `forceRefresh()` remains the explicit redraw seam for tests that need to
  assert the opt-in native refresh path.

## Native Callbacks Into The Activity

- `updateNativeActivityRef()` stores a global reference to `MainActivity` and
  caches the method IDs for `requestFile(...)`, `playTone(...)`, `stopTone()`,
  and `processCoreTasks()`. It does not redraw the LCD on reattach.
- `processCoreTasksNative()` is the re-entry hook used by
  `yieldToAndroidWithMs(...)`. It calls back into `MainActivity.processCoreTasks()`
  so queued Android-side work can run while the native core is yielding.
- `requestFile(...)` posts onto the main handler and hands control to
  `StorageAccessCoordinator`, which owns the SAF launcher registration and the
  detached file-descriptor handoff. The same coordinator also owns the
  first-run welcome-dialog handoff into the direct work-directory tree picker
  and the missing-directory recovery picker path, while `SettingsActivity`
  remains the explicit manual work-directory surface.

These callbacks are part of the interface contract, not optional convenience
hooks. If their names or signatures drift, the bridge loses storage, tone, or
yield behavior.

## Storage, Yield, And Re-Entrancy Boundary

- `hal/io.c::ioFileOpen(...)` intercepts state, program, RTF-export, and manual
  save paths into `requestAndroidFile(...)` when the Android build is active.
- `requestAndroidFile(...)` in `jni_storage.c` fully releases the recursive
  `screenMutex`, marks `isCoreBlockingForIo = true`, calls back into
  `MainActivity.requestFile(...)`, waits on `fileCond`, then reacquires
  `screenMutex` exactly as many times as it was previously held.
- `Java_com_example_r47_MainActivity_onFileSelectedNative(...)` and
  `Java_com_example_r47_MainActivity_onFileCancelledNative(...)` wake that wait
  by updating `fileDescriptor`, `fileReady`, and `fileCancelled` under
  `fileMutex`.
- `yieldToAndroidWithMs(...)` in `android_runtime.c` uses the same release,
  process, sleep, and reacquire pattern for long-running native work. It also
  advances timers every 5 ms, refreshes the LCD, and calls
  `processCoreTasksNative()` before sleeping.
- That makes `yieldToAndroidWithMs(...)` both the Android-owned mid-run bridge
  that can drain queued non-stop work while a long shared-core run is still
  executing and one of the two core-owned consumption points for the pending
  stop-refresh request published by `requestStopProgramNative()`.
- The base path configured by `nativePreInit(...)` and the SAF work-directory
  URI owned by `WorkDirectory` are separate contracts. The docs and code must
  keep them separate.
- The startup and missing-directory work-directory tree picker route stays on
  the Kotlin side in `StorageAccessCoordinator` and `WorkDirectory`; it does
  not cross the native bridge unless a later file open request reaches the
  detached-fd SAF seam.

## Event-Loop Compatibility Contract

The staged upstream core runs in `PC_BUILD` mode, so the Android bridge must
provide the small GTK and GLib event-loop API surface that pause, wait,
progress, and timer-driven code expects.

`android_runtime.c` owns these compatibility symbols:

- `g_main_context_iteration(...)`
- `gtk_main_iteration()`
- `gtk_events_pending()`
- `g_timeout_add(...)`
- `g_source_remove(...)`
- `yieldToAndroidWithMs(...)`

These are active runtime behavior, not placeholders. Changes here can break the
host workload-regression lane and Android long-running program behavior even
when the app still launches.

## Interface Change Rules

- Change canonical upstream sources for shared calculator behavior.
- Change `r47zen` only for Android runtime compatibility, JNI, HAL, or
  marshalling behavior.
- Keep Kotlin external declarations, `JNINativeMethod` registration, cached
  `jmethodID`s, and native implementations aligned in one change.
- Keep lock release and reacquire boundaries explicit on storage, refresh, and
  pause or wait paths.
- Do not reintroduce `forceRefreshNative()` or other synthetic redraw work into
  a passive lifecycle callback such as a normal Settings return.
- Do not hand-edit the build-only staged native tree as a lasting fix.
