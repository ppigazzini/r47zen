# Upstream Interface Surfaces

This page maps the exact Android-owned calls that cross into the staged
upstream core and the exact native callbacks that cross back into the activity.

Read `00-project-and-upstream.md` first. This page assumes the project and
ownership boundary is already clear.

Read this page when a task touches `MainActivity` external methods, JNI
registration, keypad-scene export, SAF-backed file requests, or pause and wait
behavior in the staged `PC_BUILD` core. Read
`80-tests-and-contracts.md` for the focused bridge, READP, and storage
verification surfaces.

## Interface Inventory

| Surface | Android side | Native bridge | Shared-core side | Sensitive detail |
| --- | --- | --- | --- | --- |
| runtime boot and attach | `NativeCoreRuntime.attach()` calls `nativePreInit()`, `initNative()`, `tick()`, and `updateNativeActivityRef()` | `jni_registration.c` plus `jni_lifecycle.c` | `setupUI()`, `doFnReset()`, `restoreCalc()`, `fnTimerConfig(...)` | `tick()` only runs when `pthread_mutex_trylock(&screenMutex)` succeeds |
| direct input dispatch | `sendKey()`, `sendSimKeyNative()`, `sendSimMenuNative()`, `sendSimFuncNative()` | `jni_input.c` | `btnPressed(...)`, `btnReleased(...)`, `showSoftmenu(...)`, `runFunction(...)` | input paths serialize on `screenMutex` and some skip while `isCoreBlockingForIo` is true |
| LCD and keypad snapshot export | `getDisplayPixels()`, `getKeypadMetaNative()`, `getKeypadLabelsNative()` | `jni_display.c` | `screenData`, visible key tables, label resolvers | `getDisplayPixels()` exits early when `screenDataDirty` is false or the lock is busy |
| native to activity callbacks | `requestFile()`, `playTone()`, `stopTone()`, `processCoreTasks()` | `updateNativeActivityRef()` caches `jmethodID`s; `processCoreTasksNative()` calls back into Java | lets long native waits service Android work | cached method IDs and Kotlin method signatures must stay aligned |
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
  `updateNativeActivityRef()`.
- `r47_native_preinit_path(...)` sets the Android base path used by
  `hal/io.c` and installs the GMP allocator hooks before the shared core starts.
- `r47_init_runtime(...)` sets the current slot, calls `setupUI()`, initializes
  LCD buffers, resets and restores calculator state, sets both
  `nextScreenRefresh` and `nextTimerRefresh`, and registers the native timer
  callbacks that the staged core expects.
- `Java_com_example_r47_MainActivity_tick(...)` is the steady-state entry point
  from the Kotlin core thread. It uses `pthread_mutex_trylock(&screenMutex)`,
  advances timers every 5 ms, and refreshes the LCD every 100 ms when the lock
  is available.

## Input Dispatch Contract

- Touch, PiP taps, physical-keyboard actions, and some display actions all land
  in `MainActivity`, then queue work through `NativeCoreRuntime.offerTask(...)`
  or call one of the direct JNI input entry points.
- `sendKey(int)` is the main numeric key path. It maps key codes `1..37` onto
  `btnPressed(...)` or `btnReleased(...)` and key codes `38..43` onto the
  dedicated function-key press and release handlers.
- `sendSimKeyNative(String, boolean, boolean)` is the string-key path used by
  the physical keyboard mapper and display actions. It bails out when
  `isCoreBlockingForIo` is set so Android does not inject keypad work while the
  core is suspended in a SAF file request.
- `sendSimMenuNative(int)` calls `showSoftmenu(...)` and then forces a screen
  refresh. `sendSimFuncNative(int)` calls `runFunction(...)` directly.
- All of these paths lock `screenMutex`, so they must stay short and must not
  add Android-side blocking work inside the native critical section.

## Display And Keypad Snapshot Export

- `NativeDisplayRefreshLoop.doFrame(...)` is the only continuous poller on the
  Android side. It requests LCD pixels plus keypad metadata and labels from the
  native bridge while the app is active.
- `getDisplayPixels(...)` copies the `400 x 240` LCD buffer from `screenData`
  only when `screenDataDirty` is true. It uses `pthread_mutex_trylock`, so a
  busy native section simply skips one frame instead of blocking the UI thread.
- `getKeypadMetaNative(...)` fills one fixed `KEYPAD_META_LENGTH` integer array
  under `screenMutex`.
- `getKeypadLabelsNative(...)` walks the visible main-key table plus the six
  softkeys under `screenMutex` and exports the current label strings.
- Kotlin converts those raw arrays into `KeypadSnapshot`, and the renderer uses
  named fields from that model instead of indexing raw native arrays again.

## Native Callbacks Into The Activity

- `updateNativeActivityRef()` stores a global reference to `MainActivity` and
  caches the method IDs for `requestFile(...)`, `playTone(...)`, `stopTone()`,
  and `processCoreTasks()`.
- `processCoreTasksNative()` is the re-entry hook used by
  `yieldToAndroidWithMs(...)`. It calls back into `MainActivity.processCoreTasks()`
  so queued Android-side work can run while the native core is yielding.
- `requestFile(...)` posts onto the main handler and hands control to
  `StorageAccessCoordinator`, which owns the SAF launcher registration and the
  detached file-descriptor handoff.

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
- The base path configured by `nativePreInit(...)` and the SAF work-directory
  URI owned by `WorkDirectory` are separate contracts. The docs and code must
  keep them separate.

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
- Change `c47-android` only for Android runtime compatibility, JNI, HAL, or
  marshalling behavior.
- Keep Kotlin external declarations, `JNINativeMethod` registration, cached
  `jmethodID`s, and native implementations aligned in one change.
- Keep lock release and reacquire boundaries explicit on storage, refresh, and
  pause or wait paths.
- Do not hand-edit the build-only staged native tree as a lasting fix.
